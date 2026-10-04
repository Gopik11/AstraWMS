package com.astrawms.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrawms.mobile.AppContainer
import com.astrawms.mobile.core.api.CountConfirm
import com.astrawms.mobile.core.api.CountedLine
import com.astrawms.mobile.core.api.LineProgress
import com.astrawms.mobile.core.api.PickConfirm
import com.astrawms.mobile.core.api.PutawayConfirm
import com.astrawms.mobile.core.api.ReceiveClose
import com.astrawms.mobile.core.api.ReceiveScan
import com.astrawms.mobile.core.api.Resolved
import com.astrawms.mobile.core.api.Task
import com.astrawms.mobile.core.api.TaskException
import com.astrawms.mobile.core.offline.Outcome
import com.astrawms.mobile.core.rfid.Epc
import com.astrawms.mobile.core.rfid.RfidAssist
import com.astrawms.mobile.core.rfid.TagBuffer
import com.astrawms.mobile.core.rfid.TagRead
import com.astrawms.mobile.core.scan.Gs1
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * RF work (scope §G.3) on the handheld: the next task of the operator's role, confirmed by scanning, as the web RF
 * screen does it, plus RFID (ADR-0027): a pallet's SSCC tag is the LPN scan, unit tags verify the picked item and
 * give quantity and serials, a read of the whole location proposes the count lines. Commands go through the offline
 * queue (ADR-0023).
 */
@Composable
fun WorkScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val site = settings.site
    val state = rememberAction()
    var task by remember { mutableStateOf<Task?>(null) }
    var noWork by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    fun fromDevice(): Task? = container.queue.offlineTasks(site).firstOrNull()

    fun next() = scope.act(state) {
        if (container.queue.pending.isNotEmpty()) {
            container.queue.sync()
            if (container.queue.pending.isNotEmpty()) {
                task = fromDevice()
                noWork = task == null
                return@act "Scans are still waiting to be sent: working on the tasks on this device"
            }
        }
        task = try {
            container.api.nextTask(site)
        } catch (e: IOException) {
            fromDevice() ?: throw e
        }
        noWork = task == null
        null
    }

    LaunchedEffect(site) { next() }

    val finished: (String) -> Unit = { message ->
        task?.let { container.queue.dropOfflineTask(site, it.id) }
        done = message
        task = null
        next()
    }

    Screen("RF work · $site", onBack) {
        done?.let { Banner(it, Color(0xFFE8F5E9), Color(0xFF1B5E20)) }
        Feedback(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { done = null; next() }) { Text(if (task == null) "Next task" else "Refresh") }
            TextButton(onClick = {
                scope.act(state) {
                    val tasks = container.api.claimBatch(site, 10)
                    container.queue.saveOfflineTasks(site, tasks)
                    "${tasks.size} task(s) on this device for offline work"
                }
            }) { Text("Download my work") }
        }
        if (noWork && task == null) Text("No work for you right now.", color = Color.Gray)
        task?.let { t ->
            key(t.id) {
                TaskHead(t)
                when (t.taskType) {
                    "PUTAWAY" -> PutawayForm(t, site, finished)
                    "PICK" -> PickForm(t, site, finished)
                    "RECEIVE" -> ReceiveForm(t, site, finished)
                    "COUNT" -> CountForm(t, site, finished)
                    "MOVE" -> CheckDigitForm(t, site, "move", "Target location check digit", "Confirm move", finished)
                    "REPLEN" -> CheckDigitForm(t, site, "replenish", "Forward location check digit", "Confirm replenishment", finished)
                    "RETURN" -> CheckDigitForm(t, site, "return", "Return location check digit", "Confirm return", finished)
                    else -> Text("Task type ${t.taskType} is not worked on RF.")
                }
            }
        }
    }
}

@Composable
private fun TaskHead(t: Task) {
    Text("${t.taskType} · ${t.status}${t.orderRef?.let { " · order $it" } ?: ""}", fontWeight = FontWeight.Bold)
}

/** Sends an RF task command now, or keeps it on the device when there is no network. */
private suspend inline fun <reified T> AppContainer.rf(site: String, taskId: String, action: String, body: T, label: String): Outcome<String?> {
    val path = api.taskPath(site, taskId, action)
    val json = api.json.encodeToString(body)
    return queue.submit(path, json, label) { api.send("POST", path, json) }
}

private suspend fun AppContainer.resolveOrNull(site: String, reads: List<TagRead>): List<Resolved>? = try {
    api.resolve(site, reads.map { it.epc }.distinct())
} catch (e: IOException) {
    null                                                    // offline: the screens fall back to decoding on the device
}

// ------------------------------------------------------------------ putaway

private val OVERRIDE_REASONS = listOf("LOCATION_FULL" to "Location full", "LOCATION_BLOCKED" to "Location blocked",
    "LOCATION_DAMAGED" to "Location damaged", "CLOSER_LOCATION" to "Closer location", "CONSOLIDATE" to "Consolidate",
    "OTHER" to "Other")

@Composable
private fun PutawayForm(t: Task, site: String, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    var lpn by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(t.targetLocation ?: "") }
    var checkDigit by remember { mutableStateOf("") }
    var overrideReason by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf("LOCATION_BLOCKED") }
    var note by remember { mutableStateOf<String?>(null) }
    val overriding = !location.trim().equals(t.targetLocation ?: "", ignoreCase = true)

    ReaderEvents(
        onTags = { reads ->
            scope.launch {
                // the pallet's SSCC tag is the LPN scan; a bin tag gives the location
                val ssccs = reads.mapNotNull { Epc.parse(it.epc)?.sscc }.distinct()
                val resolved = container.resolveOrNull(site, reads).orEmpty()
                val lpns = (ssccs + resolved.filter { it.kind == "LPN" }.mapNotNull { it.lpnId }).distinct()
                val taskLpn = t.lpnId
                lpn = when {
                    taskLpn != null && taskLpn in lpns -> taskLpn
                    lpns.size == 1 -> lpns.single()
                    else -> lpn
                }
                RfidAssist.singleLocation(resolved)?.let { location = it }
                note = when {
                    lpns.size > 1 && t.lpnId !in lpns -> "${lpns.size} pallets read, none is ${t.lpnId}: read closer"
                    lpns.isEmpty() -> "No pallet tag among ${reads.size} read(s)"
                    else -> "RFID: LPN $lpn"
                }
            }
        },
        onBarcode = { text ->
            val sscc = Gs1.parse(text)?.sscc
            if (lpn.isBlank() || text == t.lpnId || sscc != null) lpn = sscc ?: text else location = text
        },
    )
    Facts("LPN" to t.lpnId, "From" to t.fromLocation, "To" to t.targetLocation, "Why" to t.strategy)
    t.contents?.takeIf { it.isNotEmpty() }?.let { c -> Text(c.joinToString { "${qty(it.qty)} × ${it.itemNo}${it.lotNo?.let { l -> " ($l)" } ?: ""}" }, color = Color.Gray) }
    note?.let { Text(it, color = Color(0xFF1B3A5C)) }
    Field("Scan LPN (barcode or pallet tag)", lpn, { lpn = it })
    Field("Location", location, { location = it }, hint = "Change only to override the suggested location")
    if (overriding) {
        Text("Why not ${t.targetLocation}?")
        Choices(OVERRIDE_REASONS, overrideReason) { overrideReason = it }
    }
    Field("Location check digit", checkDigit, { checkDigit = it }, numeric = true)
    Feedback(state)
    PrimaryButton("Confirm putaway", enabled = !state.busy && lpn.isNotBlank() && checkDigit.isNotBlank() && (!overriding || overrideReason != null)) {
        scope.act(state) {
            val outcome = container.rf(site, t.id, "confirm",
                PutawayConfirm(lpn.trim(), location.trim().uppercase(), checkDigit.trim(), if (overriding) overrideReason else null),
                "Putaway ${t.lpnId} → ${location.uppercase()}")
            onDone(outcome.describe("Put away ${t.lpnId} to ${location.uppercase()}"))
            null
        }
    }
    SectionTitle("Report a problem")
    Choices(listOf("LOCATION_BLOCKED" to "Location blocked", "LOCATION_OCCUPIED" to "Location occupied", "LPN_NOT_FOUND" to "LPN not found"), problem) { problem = it }
    SecondaryButton("Report") {
        scope.act(state) {
            container.rf(site, t.id, "exception", TaskException(problem), "Exception $problem")
            onDone("Reported $problem; the task was re-planned")
            null
        }
    }
}

// ------------------------------------------------------------------ pick

private val PICK_SHORT_REASONS = listOf("NOT_FOUND" to "Not found", "QTY_LESS" to "Less than expected", "DAMAGED" to "Damaged",
    "WRONG_ITEM" to "Wrong item", "OTHER" to "Other")
private val PICK_SHORT_ACTIONS = listOf("REALLOCATE" to "Reallocate now", "BACKORDER" to "Backorder", "SHIP_SHORT" to "Ship short")

@Composable
private fun PickForm(t: Task, site: String, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    var checkDigit by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf(qty(t.qty)) }
    var serials by remember { mutableStateOf("") }
    var shortReason by remember { mutableStateOf<String?>(null) }
    var shortAction by remember { mutableStateOf("REALLOCATE") }
    var note by remember { mutableStateOf<String?>(null) }
    val requested = t.qty ?: 0.0
    val picked = qtyText.toDoubleOrNull()
    val short = picked != null && picked < requested

    ReaderEvents(
        onTags = { reads ->
            scope.launch {
                val resolved = container.resolveOrNull(site, reads)
                if (resolved == null) {
                    // offline: the server checks the GTIN on confirm; count this GTIN's tags as units
                    val units = reads.mapNotNull { Epc.parse(it.epc) }.filter { it.scheme == Epc.Scheme.SGTIN }
                    val first = units.firstOrNull() ?: return@launch
                    item = first.hex
                    qtyText = qty(units.count { it.gtin == first.gtin }.toDouble())
                    note = "Offline: ${units.size} unit tag(s); the item is checked when sent"
                    return@launch
                }
                val tally = RfidAssist.tally(resolved, t.itemNo ?: "", t.ownerId)
                val others = resolved.count { it.kind == "ITEM" && !it.itemNo.equals(t.itemNo, ignoreCase = true) }
                if (tally.tags == 0) {
                    note = "None of the ${resolved.size} tag(s) is ${t.itemNo}" + if (others > 0) " ($others other item tag(s))" else ""
                    return@launch
                }
                item = resolved.first { it.kind == "ITEM" && it.itemNo.equals(t.itemNo, ignoreCase = true) }.epc ?: t.itemNo ?: ""
                qtyText = qty(tally.qty)
                if (tally.serials.isNotEmpty()) serials = tally.serials.joinToString("\n")
                note = buildString {
                    append("RFID: ${qty(tally.qty)} ${tally.uom ?: t.uom ?: ""} of ${t.itemNo} (${tally.tags} tag(s))")
                    if (tally.qty > requested) append(" — more than requested: put ${qty(tally.qty - requested)} back")
                    if (others > 0) append(" · $others tag(s) of other items ignored")
                }
            }
        },
        onBarcode = { text -> item = text },
    )
    Facts("From" to t.fromLocation, "Item" to listOfNotNull(t.itemNo, t.lotNo?.let { "lot $it" }).joinToString(" · "),
        "Quantity" to "${qty(t.qty)} ${t.uom ?: ""}", "To" to listOfNotNull(t.targetLocation, t.toLpn).joinToString(" · "))
    Field("Source location check digit", checkDigit, { checkDigit = it }, numeric = true)
    note?.let { Text(it, color = Color(0xFF1B3A5C)) }
    Field("Scan item, GTIN or unit tags", item, { item = it }, hint = "A location-only pick is not accepted")
    Field("Quantity picked", qtyText, { qtyText = it }, numeric = true,
        hint = if (short) "Less than requested: this is a short pick" else null)
    if (short) {
        Text("Short pick: ${qty(requested - (picked ?: 0.0))} ${t.uom ?: ""} missing")
        Choices(PICK_SHORT_REASONS, shortReason) { shortReason = it }
        Choices(PICK_SHORT_ACTIONS, shortAction) { shortAction = it }
    }
    OutlinedTextField(serials, { serials = it }, label = { Text("Serial numbers (serial-tracked items)") }, minLines = 2,
        modifier = Modifier.fillMaxWidth())
    Feedback(state)
    PrimaryButton(if (short) "Confirm short pick" else "Confirm pick",
        enabled = !state.busy && checkDigit.isNotBlank() && item.isNotBlank() && picked != null && picked <= requested && (!short || shortReason != null)) {
        scope.act(state) {
            val body = PickConfirm(checkDigit.trim(), item.trim(), picked ?: 0.0, splitSerials(serials),
                if (short) shortReason else null, if (short) shortAction else null)
            val outcome = container.rf(site, t.id, "pick", body, "Pick ${t.itemNo} × ${qty(picked)} for ${t.orderRef}")
            onDone(outcome.describe("Picked ${qty(picked)} × ${t.itemNo} for ${t.orderRef}"))
            null
        }
    }
}

// ------------------------------------------------------------------ receive

private val SHORT_REASONS = listOf("SHORT_VENDOR" to "Short from vendor", "DAMAGED" to "Damaged", "REFUSED" to "Refused",
    "IN_TRANSIT" to "In transit")

/**
 * RF receiving of an ASN or RMA (ADR-0019): reading the unit / case tags of what arrived proposes the item, the
 * quantity in base units and the serials of serial-tracked units; the pallet's SSCC tag becomes the LPN.
 */
@Composable
private fun ReceiveForm(t: Task, site: String, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    val rma = t.receiveKind == "RMA"
    val expected = t.expectedLines.orEmpty()
    var doc by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var uom by remember { mutableStateOf(expected.firstOrNull()?.uom ?: "EA") }
    var lot by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var serials by remember { mutableStateOf("") }
    var lpn by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var checkDigit by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf("A") }
    var scanId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var note by remember { mutableStateOf<String?>(null) }
    val reasons = remember { mutableStateMapOf<String, String>() }
    val progress = remember { mutableStateListOf<LineProgress>() }

    fun reload() = scope.launch {
        val docNo = t.docNo ?: return@launch
        runCatching { container.api.receiveProgress(site, docNo, rma) }.onSuccess { progress.clear(); progress.addAll(it) }
    }
    LaunchedEffect(t.id) { reload() }

    ReaderEvents(
        onTags = { reads ->
            scope.launch {
                val resolved = container.resolveOrNull(site, reads)
                if (resolved == null) {
                    note = "Offline: tags cannot be matched to items; scan the item barcode"
                    return@launch
                }
                val onDoc = expected.map { it.itemNo }.toSet()
                val candidates = RfidAssist.items(resolved)
                val chosen = candidates.firstOrNull { it.first in onDoc }?.first ?: candidates.firstOrNull()?.first
                if (chosen != null) {
                    val tally = RfidAssist.tally(resolved, chosen)
                    item = chosen
                    qtyText = qty(tally.qty)
                    tally.uom?.let { uom = it }
                    resolved.firstOrNull { it.itemNo == chosen }?.ownerId?.let { if (owner.isBlank()) owner = it }
                    serials = tally.serials.joinToString("\n")
                }
                if (lpn.isBlank()) RfidAssist.singleLpn(resolved)?.let { lpn = it }
                val unknown = resolved.count { it.problem == "GTIN_UNKNOWN" }
                note = buildString {
                    if (chosen == null) append("No item tags among ${resolved.size} read(s)")
                    else append("RFID: ${qtyText} $uom of $chosen")
                    if (candidates.size > 1) append(" · also read: ${candidates.filter { it.first != chosen }.joinToString { "${it.first} (${it.second})" }}")
                    if (unknown > 0) append(" · $unknown tag(s) of unknown GTINs")
                    if (lpn.isNotBlank()) append(" · LPN $lpn")
                }
            }
        },
        onBarcode = { text ->
            val gs1 = Gs1.parse(text)
            when {
                doc.isBlank() && gs1 == null -> doc = text
                gs1?.itemGtin != null -> {
                    item = text                                      // the server resolves the GTIN on the document
                    if (lot.isBlank()) lot = gs1.lot ?: ""
                    if (expiry.isBlank()) expiry = gs1.expiry?.toString() ?: ""
                    if (qtyText.isBlank()) qtyText = gs1.count ?: ""
                    if (serials.isBlank()) serials = gs1.serial ?: ""
                    if (lpn.isBlank()) lpn = gs1.sscc ?: ""
                    note = "GS1: GTIN ${gs1.itemGtin}${gs1.lot?.let { " · lot $it" } ?: ""}"
                }
                gs1?.sscc != null -> lpn = gs1.sscc.orEmpty()
                item.isBlank() -> item = text
                else -> lpn = text
            }
        },
    )

    Facts((if (rma) "Return" else "Delivery") to t.docNo, (if (rma) "Customer" else "Vendor") to t.partner)
    val shown = progress.ifEmpty { expected.map { LineProgress(it.lineRef, it.itemNo, it.qty, 0.0, it.uom) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            shown.forEach { l ->
                Text("${l.lineRef} · ${l.itemNo}: ${qty(l.qtyReceived)} of ${qty(l.qtyExpected)} ${l.uom}${if (l.qtyReceived >= l.qtyExpected) " ✓" else ""}")
            }
            if (shown.isEmpty()) Text(if (rma) "Return without RMA: scan what arrived" else "No lines", color = Color.Gray)
        }
    }
    note?.let { Text(it, color = Color(0xFF1B3A5C)) }
    Field(if (rma) "Scan return / RMA no." else "Scan delivery no.", doc, { doc = it })
    Field("Item (number, GTIN, GS1 label or tags)", item, { item = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Qty", qtyText, { qtyText = it }, numeric = true, modifier = Modifier.weight(1f))
        Field("UoM", uom, { uom = it.uppercase() }, modifier = Modifier.weight(1f))
    }
    if (rma && expected.isEmpty()) Field("Owner", owner, { owner = it.uppercase() })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Lot", lot, { lot = it }, modifier = Modifier.weight(1f))
        if (!rma) Field("Expiry (YYYY-MM-DD)", expiry, { expiry = it }, modifier = Modifier.weight(1f))
    }
    OutlinedTextField(serials, { serials = it }, label = { Text("Serial numbers (serial-tracked items)") }, minLines = 2,
        modifier = Modifier.fillMaxWidth())
    if (rma) {
        Text("Condition")
        Choices(listOf("A", "B", "C", "D", "E").map { it to it }, grade) { grade = it }
    }
    Field("LPN (pallet label or tag)", lpn, { lpn = it }, hint = if (rma) "Blank: one LPN per unit" else "Blank for loose stock")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(if (rma) "Returns / dock location" else "Dock location", location, { location = it }, modifier = Modifier.weight(2f))
        Field("Check digit", checkDigit, { checkDigit = it }, numeric = true, modifier = Modifier.weight(1f))
    }
    Feedback(state)
    val q = qtyText.toDoubleOrNull()
    PrimaryButton("Confirm receipt", enabled = !state.busy && doc.isNotBlank() && item.isNotBlank() && q != null && q > 0 &&
        location.isNotBlank() && checkDigit.isNotBlank()) {
        scope.act(state) {
            val body = ReceiveScan(
                scanId = scanId, docNo = doc.trim(), itemNo = item.trim().let { if (Gs1.parse(it) != null || Epc.parse(it) != null) it else it.uppercase() },
                ownerId = owner.trim().ifEmpty { null }, qty = q ?: 0.0, uom = uom.trim().uppercase(),
                lotNo = lot.trim().ifEmpty { null }, expiryDate = expiry.trim().ifEmpty { null },
                serials = splitSerials(serials), lpnId = lpn.trim().ifEmpty { null }, locationId = location.trim().uppercase(),
                checkDigit = checkDigit.trim(), conditionGrade = if (rma) grade else null,
            )
            val outcome = container.rf(site, t.id, "receive", body, "Receive ${qty(q)} ${body.uom} ${body.itemNo} on ${t.docNo}")
            scanId = UUID.randomUUID().toString()             // the next scan is a new one; a retry keeps the same ID
            item = ""; qtyText = ""; lot = ""; expiry = ""; serials = ""; lpn = ""; note = null
            reload()
            outcome.describe("Received ${qty(q)} ${body.uom} ${body.itemNo}")
        }
    }
    SectionTitle("Finish ${if (rma) "return" else "delivery"}")
    val short = if (rma) emptyList() else progress.filter { it.qtyReceived < it.qtyExpected }
    short.forEach { l ->
        Text("${l.lineRef} · ${l.itemNo} (${qty(l.qtyReceived)} of ${qty(l.qtyExpected)}): reason")
        Choices(SHORT_REASONS, reasons[l.lineRef]) { reasons[l.lineRef] = it }
    }
    PrimaryButton("Close and confirm to ERP", enabled = !state.busy && short.all { reasons[it.lineRef] != null }) {
        scope.act(state) {
            val outcome = container.rf(site, t.id, "receive/close", ReceiveClose(if (rma) emptyMap() else reasons.toMap()), "Close ${t.docNo}")
            onDone(outcome.describe("${t.docNo} closed and confirmed to the ERP"))
            null
        }
    }
    SecondaryButton("Stop for now") {
        scope.act(state) {
            container.rf(site, t.id, "release", emptyMap<String, String>(), "Hand back ${t.docNo}")
            onDone("${t.docNo} handed back to the queue")
            null
        }
    }
}

// ------------------------------------------------------------------ count

private class CountRow(owner: String = "", item: String = "", lot: String = "", lpn: String = "", qty: String = "") {
    var owner by mutableStateOf(owner)
    var item by mutableStateOf(item)
    var lot by mutableStateOf(lot)
    var lpn by mutableStateOf(lpn)
    var qty by mutableStateOf(qty)
}

/**
 * Blind cycle count (INV-003). With RFID the counter reads the whole location and the server proposes the lines from
 * the tags (an LPN tag counts its contents); the counter checks them and adds untagged stock. The system quantity is
 * never shown here.
 */
@Composable
private fun CountForm(t: Task, site: String, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    val buffer = remember { TagBuffer() }
    val tags by buffer.tags.collectAsStateWithLifecycle()
    var checkDigit by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf(CountRow()) }
    var note by remember { mutableStateOf<String?>(null) }

    ReaderEvents(onTags = { buffer.add(it) }, onBarcode = { text -> rows.lastOrNull()?.let { if (it.item.isBlank()) it.item = text } })

    Text(if ((t.countSequence ?: 1) > 1) "Recount ${t.countSequence}: count independently." else "Count everything in this location.")
    Facts("Location" to t.fromLocation)
    Field("Location check digit", checkDigit, { checkDigit = it }, numeric = true)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("RFID: ${tags.size} tag(s) read in this location", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = tags.isNotEmpty() && !state.busy, onClick = {
                    scope.act(state) {
                        val r = container.api.reconcile(site, t.fromLocation ?: "", buffer.epcs())
                        val lines = RfidAssist.countLines(r)
                        rows.clear()
                        lines.forEach { rows += CountRow(it.ownerId, it.itemNo, it.lotNo ?: "", it.lpnId ?: "", qty(it.qty)) }
                        rows += CountRow(lines.lastOrNull()?.ownerId ?: "")
                        note = "${lines.size} line(s) from ${r.tags} tag(s)" +
                            (if (r.unresolved.isNotEmpty()) "; ${r.unresolved.size} tag(s) not recognised" else "") +
                            ". Add stock that has no tag."
                        null
                    }
                }) { Text("Use the tags") }
                TextButton(onClick = { buffer.clear(); note = null }) { Text("Clear") }
            }
        }
    }
    note?.let { Text(it, color = Color(0xFF1B3A5C)) }
    rows.forEachIndexed { i, r ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Owner", r.owner, { r.owner = it.uppercase() }, modifier = Modifier.weight(1f))
                    Field("Item", r.item, { r.item = it.uppercase() }, modifier = Modifier.weight(2f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Lot", r.lot, { r.lot = it }, modifier = Modifier.weight(1f))
                    Field("LPN", r.lpn, { r.lpn = it }, modifier = Modifier.weight(1f))
                    Field("Qty", r.qty, { r.qty = it }, numeric = true, modifier = Modifier.weight(1f))
                }
                if (rows.size > 1) TextButton(onClick = { rows.removeAt(i) }) { Text("Remove") }
            }
        }
    }
    SecondaryButton("Add item") { rows += CountRow(rows.lastOrNull()?.owner ?: "") }
    val filled = rows.filter { it.item.isNotBlank() && it.qty.toDoubleOrNull() != null }
    Feedback(state)
    PrimaryButton(if (filled.isEmpty()) "Location is empty" else "Submit count (${filled.size} line(s))",
        enabled = !state.busy && checkDigit.isNotBlank() && filled.all { it.owner.isNotBlank() }) {
        scope.act(state) {
            val body = CountConfirm(checkDigit.trim(), filled.map {
                CountedLine(it.owner.trim(), it.item.trim(), it.lot.trim().ifEmpty { null }, it.lpn.trim().ifEmpty { null }, it.qty.toDouble())
            })
            val outcome = container.rf(site, t.id, "count", body, "Count ${t.fromLocation}")
            // where the tags were seen: trace for commissioned tags (best effort)
            if (tags.isNotEmpty()) runCatching { container.api.sightings(site, buffer.epcs(), t.fromLocation) }
            onDone(outcome.describe("Counted ${t.fromLocation}: ${if (filled.isEmpty()) "empty" else "${filled.size} line(s)"}"))
            null
        }
    }
}

// ------------------------------------------------------------------ move / replenish / return

@Composable
private fun CheckDigitForm(t: Task, site: String, action: String, label: String, button: String, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    var checkDigit by remember { mutableStateOf("") }
    if (action == "return") Text("Order ${t.orderRef} was cancelled: bring the picked stock back.")
    Facts("Take from" to listOfNotNull(t.fromLocation, t.lpnId?.ifEmpty { null }).joinToString(" · "),
        "Item" to "${qty(t.qty)} ${t.uom ?: ""} × ${t.itemNo}${t.lotNo?.let { " · lot $it" } ?: ""}",
        "Drop at" to t.targetLocation)
    Field(label, checkDigit, { checkDigit = it }, numeric = true)
    Feedback(state)
    PrimaryButton(button, enabled = !state.busy && checkDigit.isNotBlank()) {
        scope.act(state) {
            val outcome = container.rf(site, t.id, action, com.astrawms.mobile.core.api.CheckDigit(checkDigit.trim()),
                "${t.taskType} ${t.itemNo} → ${t.targetLocation}")
            onDone(outcome.describe("${t.taskType.lowercase().replaceFirstChar { it.uppercase() }} done: ${qty(t.qty)} × ${t.itemNo} to ${t.targetLocation}"))
            null
        }
    }
}

private fun splitSerials(text: String): List<String> = text.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
