package com.astrawms.mobile.core.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** An answer from the server that is not a success; [code] is the AstraWMS problem code (e.g. TSK_WRONG_LPN). */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * REST client for the AstraWMS API behind the gateway (README "APIs"). Every request carries the user's bearer token
 * (ADR-0010); commands carry an Idempotency-Key where the endpoint wants one (NFR-123). Network failures surface as
 * [IOException], so RF commands can be kept on the device and sent later (ADR-0023); errors from the server as
 * [ApiException]. Reads are retried twice on 502/503/504 (a service restarting during a deployment).
 */
class AstraApi(
    private val baseUrl: () -> String,
    private val token: suspend () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // ------------------------------------------------------------------ transport

    /** Sends a request; returns the body, or null for 204. */
    suspend fun send(method: String, path: String, body: String? = null, idempotencyKey: String? = null): String? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl().trimEnd('/') + path)
            token()?.let { builder.header("Authorization", "Bearer $it") }
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            val requestBody = when {
                body != null -> body.toRequestBody(JSON)
                method == "GET" || method == "HEAD" -> null
                else -> "{}".toRequestBody(JSON)
            }
            builder.method(method, requestBody)
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw problem(response.code, text)
                }
                if (response.code == 204 || text.isEmpty()) null else text
            }
        }

    private fun problem(status: Int, text: String): ApiException {
        val p = try {
            json.decodeFromString(Problem.serializer(), text)
        } catch (e: Exception) {
            // Not JSON: a gateway page (502 while a service restarts) or a proxy error.
            Problem(title = "HTTP $status", detail = if (status >= 502) "The service is restarting or unavailable; try again in a moment" else text.take(200))
        }
        return ApiException(status, p.code ?: "HTTP_$status", p.detail ?: p.title ?: "HTTP $status")
    }

    private suspend fun get(path: String): String? {
        var attempt = 0
        while (true) {
            try {
                return send("GET", path)
            } catch (e: ApiException) {
                if (attempt >= 2 || e.status !in RETRYABLE) throw e
                delay(if (attempt == 0) 800 else 2000)
                attempt++
            }
        }
    }

    private inline fun <reified T> decode(text: String?): T = json.decodeFromString(text ?: error("empty response"))

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    private fun site(site: String) = "/api/v1/sites/${enc(site)}"

    // ------------------------------------------------------------------ RF tasks

    /** The operator's next task, or null when there is no work (204). */
    suspend fun nextTask(site: String): Task? = send("POST", "${site(site)}/tasks/next")?.let { decode(it) }

    /** Claims up to [count] tasks onto the device for offline work (ADR-0023). */
    suspend fun claimBatch(site: String, count: Int = 10): List<Task> =
        decode(send("POST", "${site(site)}/tasks/claim-batch?count=$count"))

    suspend fun task(site: String, id: String): Task = decode(get("${site(site)}/tasks/${enc(id)}"))

    fun taskPath(site: String, id: String, action: String) = "${site(site)}/tasks/${enc(id)}/$action"

    suspend fun confirmPutaway(site: String, id: String, body: PutawayConfirm): String? =
        send("POST", taskPath(site, id, "confirm"), encode(body))

    suspend fun confirmPick(site: String, id: String, body: PickConfirm): String? =
        send("POST", taskPath(site, id, "pick"), encode(body))

    suspend fun receive(site: String, id: String, body: ReceiveScan): String? =
        send("POST", taskPath(site, id, "receive"), encode(body))

    suspend fun closeReceive(site: String, id: String, body: ReceiveClose): String? =
        send("POST", taskPath(site, id, "receive/close"), encode(body))

    suspend fun release(site: String, id: String): String? = send("POST", taskPath(site, id, "release"))

    suspend fun confirmCount(site: String, id: String, body: CountConfirm): String? =
        send("POST", taskPath(site, id, "count"), encode(body))

    /** MOVE, REPLEN and RETURN tasks are confirmed with the check digit of where the stock is dropped. */
    suspend fun confirmWithCheckDigit(site: String, id: String, action: String, checkDigit: String): String? =
        send("POST", taskPath(site, id, action), encode(CheckDigit(checkDigit)))

    suspend fun exception(site: String, id: String, reason: String): String? =
        send("POST", taskPath(site, id, "exception"), encode(TaskException(reason)))

    /** The server wins on an offline conflict (ADR-0024): the task goes to exception for a supervisor. */
    suspend fun syncConflict(taskPath: String, detail: String) {
        send("POST", "$taskPath/sync-conflict", json.encodeToString(mapOf("detail" to detail)))
    }

    /** Receipt (ASN) or return (RMA) progress per line for the RF receive screen. */
    suspend fun receiveProgress(site: String, docNo: String, rma: Boolean): List<LineProgress> {
        val root = json.parseToJsonElement(get("${site(site)}/${if (rma) "returns" else "receipts"}/${enc(docNo)}") ?: "{}").jsonObject
        val lines = (root["lines"] as? JsonArray) ?: return emptyList()
        return lines.map { it.jsonObject }.map { l ->
            LineProgress(
                lineRef = l.str("erpLineRef") ?: l.str("erp_line_ref") ?: "",
                itemNo = l.str("itemNo") ?: l.str("item_no") ?: "",
                qtyExpected = l.num("qtyExpected") ?: l.num("qty_expected") ?: 0.0,
                qtyReceived = l.num("qtyReceived") ?: l.num("qty_received") ?: 0.0,
                uom = l.str("uom") ?: "",
            )
        }
    }

    // ------------------------------------------------------------------ RFID (ADR-0027)

    private fun rfid(site: String) = "${site(site)}/inventory/rfid"

    /** What the server makes of tags: unit (item, serial), LPN, location or asset, with problems. */
    suspend fun resolve(site: String, reads: List<String>): List<Resolved> =
        if (reads.isEmpty()) emptyList() else decode(send("POST", "${rfid(site)}/resolve", encode(Reads(reads))))

    /** The tags read at a location against its stock, with the count lines an RF count can submit. */
    suspend fun reconcile(site: String, locationId: String, reads: List<String>): Reconciliation =
        decode(send("POST", "${rfid(site)}/locations/${enc(locationId)}/reconcile", encode(Reads(reads))))

    suspend fun sightings(site: String, reads: List<String>, locationId: String?): String? =
        send("POST", "${rfid(site)}/sightings", encode(Sighting(reads, locationId)))

    suspend fun commission(site: String, body: Commission, idempotencyKey: String): TagView =
        decode(send("POST", "${rfid(site)}/tags", encode(body), idempotencyKey))

    suspend fun retire(site: String, epc: String, idempotencyKey: String): TagView =
        decode(send("POST", "${rfid(site)}/tags/${enc(epc)}/retire", null, idempotencyKey))

    suspend fun tag(site: String, epc: String): TagView = decode(get("${rfid(site)}/tags/${enc(epc)}"))

    companion object {
        val JSON = "application/json".toMediaType()
        private val RETRYABLE = setOf(502, 503, 504)

        fun enc(segment: String): String = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    }
}
