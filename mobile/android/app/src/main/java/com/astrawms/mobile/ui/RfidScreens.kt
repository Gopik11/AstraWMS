package com.astrawms.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrawms.mobile.core.api.Commission
import com.astrawms.mobile.core.api.Reconciliation
import com.astrawms.mobile.core.api.Resolved
import com.astrawms.mobile.core.api.TagView
import com.astrawms.mobile.core.rfid.Epc
import com.astrawms.mobile.core.rfid.SeenTag
import com.astrawms.mobile.core.rfid.TagBuffer
import com.astrawms.mobile.core.rfid.proximity
import com.astrawms.mobile.core.scan.Gs1
import java.util.UUID

private val Good = Color(0xFF1B5E20)
private val Bad = Color(0xFFB71C1C)
private val Warn = Color(0xFF8D4F00)

/**
 * RFID location check (ADR-0027): read everything in a bin and compare it with the stock there — pallets found,
 * missing and unexpected, read vs expected per item, lot and LPN, serials not read. A supervisor's or analyst's view;
 * a counter's blind count uses the count task, which never shows the system quantity.
 */
@Composable
fun LocationCheckScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val site = container.settings.current.site
    val state = rememberAction()
    val buffer = remember { TagBuffer() }
    val tags by buffer.tags.collectAsStateWithLifecycle()
    var location by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Reconciliation?>(null) }

    ReaderEvents(
        onTags = { reads ->
            buffer.add(reads)
            result = null
        },
        onBarcode = { text -> location = text.uppercase() },
    )
    Screen("Check a location", onBack) {
        Field("Location (scan the bin label)", location, { location = it.uppercase() })
        Text("${tags.size} tag(s) read. Read the whole location, then check.", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { buffer.clear(); result = null }) { Text("Clear reads") }
        }
        Feedback(state)
        PrimaryButton("Check against stock", enabled = !state.busy && location.isNotBlank()) {
            scope.act(state) {
                result = container.api.reconcile(site, location.trim(), buffer.epcs())
                null
            }
        }
        result?.let { r -> ReconciliationView(r) }
        result?.let {
            SecondaryButton("Record where these tags were seen") {
                scope.act(state) {
                    container.api.sightings(site, buffer.epcs(), location.trim())
                    "Last seen at ${location.trim()} recorded for commissioned tags"
                }
            }
        }
    }
}

@Composable
private fun ReconciliationView(r: Reconciliation) {
    val ok = r.lines.all { it.variance == 0.0 } && r.lpns.all { it.result == "FOUND" } && r.serialsNotRead.isEmpty() &&
        r.unexpectedUnits.isEmpty()
    Banner(if (ok) "Location ${r.locationId} matches the stock (${r.tags} tag(s))" else "Differences at ${r.locationId}",
        if (ok) Color(0xFFE8F5E9) else Color(0xFFFFF3E0), if (ok) Good else Warn)
    if (r.lpns.isNotEmpty()) {
        SectionTitle("Pallets / LPNs")
        r.lpns.forEach { l ->
            val color = when (l.result) { "FOUND" -> Good; "MISSING" -> Bad; else -> Warn }
            Text("${l.lpnId}: ${l.result}${if (l.result == "UNEXPECTED") " (system: ${l.systemLocation ?: "not in stock"})" else ""}", color = color)
        }
    }
    if (r.lines.isNotEmpty()) {
        SectionTitle("Items")
        r.lines.forEach { l ->
            val color = if (l.variance == 0.0) Good else if (l.variance < 0) Bad else Warn
            Text("${l.itemNo}${if (l.lotNo.isNotEmpty()) " lot ${l.lotNo}" else ""}${if (l.lpnId.isNotEmpty()) " · ${l.lpnId}" else " · loose"}: " +
                "read ${qty(l.readQty)} of ${qty(l.expectedQty)}${if (l.variance != 0.0) " (${if (l.variance > 0) "+" else ""}${qty(l.variance)})" else ""}",
                color = color)
        }
        Text("Untagged stock reads as 0: check those items by eye.", color = Color.Gray, fontSize = 12.sp)
    }
    if (r.serialsNotRead.isNotEmpty()) {
        SectionTitle("Serials not read")
        Text(r.serialsNotRead.joinToString { "${it.itemNo} ${it.serialNo}" }, color = Bad)
    }
    if (r.unexpectedUnits.isNotEmpty()) {
        SectionTitle("Units the system has elsewhere")
        r.unexpectedUnits.forEach { u -> Text("${u.itemNo} ${u.serialNo ?: ""}: system ${u.locationId ?: "not in stock"}", color = Warn) }
    }
    if (r.unresolved.isNotEmpty()) {
        SectionTitle("Tags not recognised")
        r.unresolved.forEach { u -> Text("${u.epc ?: u.read}: ${u.problem}", color = Color.Gray, fontSize = 12.sp) }
    }
}

/**
 * Identify tags (what is this pallet, this unit, this bin?) and find one: pick a tag and walk; the proximity bar
 * follows its signal strength on readers that report RSSI.
 */
@Composable
fun TagLookupScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val site = container.settings.current.site
    val state = rememberAction()
    val buffer = remember { TagBuffer() }
    val tags by buffer.tags.collectAsStateWithLifecycle()
    var resolved by remember { mutableStateOf<Map<String, Resolved>>(emptyMap()) }
    var finding by remember { mutableStateOf<String?>(null) }

    ReaderEvents(onTags = { buffer.add(it) }, onBarcode = { text ->
        // a scanned SSCC label or GTIN: find its tag
        val gs1 = Gs1.parse(text)
        finding = tags.firstOrNull { it.decoded?.sscc == gs1?.sscc && gs1?.sscc != null }?.key ?: finding
    })
    Screen("Identify / find tags", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { buffer.clear(); resolved = emptyMap(); finding = null }) { Text("Clear") }
            TextButton(enabled = tags.isNotEmpty() && !state.busy, onClick = {
                scope.act(state) {
                    val r = container.api.resolve(site, buffer.epcs())
                    resolved = tags.zip(buffer.epcs()).mapNotNull { (seen, epc) -> r.firstOrNull { it.read == epc }?.let { seen.key to it } }.toMap()
                    "${r.size} tag(s) identified"
                }
            }) { Text("Identify") }
        }
        Feedback(state)
        val target = tags.firstOrNull { it.key == finding }
        if (target != null) FindCard(target, container.readers.current.reportsRssi) { finding = null }
        Text("${tags.size} tag(s)", fontWeight = FontWeight.SemiBold)
        tags.sortedByDescending { it.bestRssi ?: Int.MIN_VALUE }.forEach { t ->
            TagRow(t, resolved[t.key]) { finding = t.key }
        }
    }
}

@Composable
private fun TagRow(t: SeenTag, r: Resolved?, onFind: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onFind)) {
        Column(Modifier.padding(10.dp)) {
            Text(describe(t, r), fontWeight = FontWeight.SemiBold)
            Text(t.decoded?.uri ?: t.raw, fontSize = 11.sp, color = Color.Gray)
            Text("${t.count} read(s)${t.bestRssi?.let { " · best $it dBm" } ?: ""}${r?.problem?.let { " · $it" } ?: ""}",
                fontSize = 12.sp, color = if (r?.problem != null) Warn else Color.Gray)
        }
    }
}

private fun describe(t: SeenTag, r: Resolved?): String {
    val d = t.decoded
    return when {
        r?.kind == "ITEM" -> "${r.itemNo} · ${qty(r.baseQty)} ${r.baseUom ?: r.uom ?: ""}${r.serialNo?.let { " · SN $it" } ?: ""}" +
            (r.locationId?.let { " · at $it" } ?: "")
        r?.kind == "LPN" -> "LPN ${r.lpnId}${r.locationId?.let { " at $it" } ?: " (not in stock)"}"
        r?.kind == "LOCATION" -> "Location ${r.locationId}"
        r?.kind == "ASSET" -> "Asset ${d?.uri ?: ""}"
        d == null -> "Unknown tag ${t.raw}"
        d.scheme == Epc.Scheme.SGTIN -> "GTIN ${d.gtin} · serial ${d.serial}"
        d.scheme == Epc.Scheme.SSCC -> "SSCC ${d.sscc}"
        else -> d.scheme.name
    }
}

@Composable
private fun FindCard(t: SeenTag, rssi: Boolean, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Finding ${t.decoded?.sscc ?: t.decoded?.gtin ?: t.key}", fontWeight = FontWeight.Bold)
            val p = proximity(t.lastRssi)
            if (rssi && p != null) {
                Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFE0E0E0))) {
                    Box(Modifier.fillMaxWidth(p / 100f).fillMaxHeight().background(if (p > 70) Good else if (p > 35) Color(0xFFF5A623) else Bad))
                }
                Text("Signal ${t.lastRssi} dBm: ${if (p > 70) "very close" else if (p > 35) "near" else "far"}")
            } else {
                Text("Seen ${t.count} time(s); this reader gives no signal strength: move until it is read.")
            }
            TextButton(onClick = onStop) { Text("Stop finding") }
        }
    }
}

/**
 * Commission a tag (ADR-0027): bind the tag read (the strongest one) to an LPN, a unit (item + serial) or a bin; or
 * have the WMS encode a GS1 EPC (SSCC-96 for an SSCC LPN, SGTIN-96 for a numeric serial) to write with an RFID
 * printer or encoder. Retire a tag that is reused or destroyed.
 */
@Composable
fun CommissionScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings = container.settings.current
    val state = rememberAction()
    val buffer = remember { TagBuffer() }
    val tags by buffer.tags.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf("LPN") }
    var lpn by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TagView?>(null) }
    val strongest = tags.maxByOrNull { it.bestRssi ?: Int.MIN_VALUE }

    ReaderEvents(onTags = { buffer.add(it) }, onBarcode = { text ->
        val gs1 = Gs1.parse(text)
        when (target) {
            "LPN" -> lpn = gs1?.sscc ?: text
            "ITEM" -> if (gs1?.serial != null) serial = gs1.serial.orEmpty() else item = text.uppercase()
            else -> location = text.uppercase()
        }
    })
    Screen("Commission a tag", onBack) {
        Choices(listOf("LPN" to "Pallet / LPN", "ITEM" to "Unit (item + serial)", "LOCATION" to "Bin"), target) { target = it }
        when (target) {
            "LPN" -> Field("LPN", lpn, { lpn = it })
            "ITEM" -> {
                Field("Owner", owner, { owner = it.uppercase() })
                Field("Item", item, { item = it.uppercase() })
                Field("Serial number", serial, { serial = it })
            }
            else -> Field("Location", location, { location = it.uppercase() })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(if (strongest == null) "Read the tag to bind (hold it close; the strongest tag is used)"
                    else "Tag: ${strongest.decoded?.uri ?: strongest.raw}", fontWeight = FontWeight.SemiBold)
                if (tags.size > 1) Text("${tags.size} tags in the field: hold only the one to bind close", color = Warn, fontSize = 12.sp)
                TextButton(onClick = { buffer.clear() }) { Text("Clear") }
            }
        }
        fun request(epc: String?) = Commission(
            epc = epc,
            ownerId = owner.ifBlank { null }.takeIf { target == "ITEM" },
            itemNo = item.ifBlank { null }.takeIf { target == "ITEM" },
            serialNo = serial.ifBlank { null }.takeIf { target == "ITEM" },
            lpnId = lpn.trim().ifBlank { null }.takeIf { target == "LPN" },
            locationId = location.trim().ifBlank { null }.takeIf { target == "LOCATION" },
            companyPrefixLength = settings.companyPrefixLength,
        )
        Feedback(state)
        PrimaryButton("Bind the tag read", enabled = !state.busy && strongest != null) {
            scope.act(state) {
                val epc = strongest?.decoded?.hex ?: strongest?.let { Epc.hexOf(it.raw) ?: it.raw }
                result = container.api.commission(settings.site, request(epc), "rfid-${UUID.randomUUID()}")
                "Tag commissioned"
            }
        }
        if (target != "LOCATION") {
            SecondaryButton("Encode a new GS1 tag (no tag read)", enabled = !state.busy) {
                scope.act(state) {
                    result = container.api.commission(settings.site, request(null), "rfid-${UUID.randomUUID()}")
                    "Write this EPC to the tag with your RFID printer or encoder"
                }
            }
        }
        result?.let { t ->
            Facts("EPC" to t.epc, "URI" to t.uri, "Bound to" to (t.lpnId?.let { "LPN $it" } ?: t.locationId?.let { "bin $it" }
                ?: "${t.itemNo}${t.serialNo?.let { " SN $it" } ?: ""}"), "Status" to t.status)
            if (t.status == "ACTIVE" && container.auth.session.value?.hasRole("INV_ANALYST", "INV_MANAGER", "SUPERVISOR") == true) {
                SecondaryButton("Retire this tag") {
                    scope.act(state) {
                        result = container.api.retire(settings.site, t.epc, "rfid-${UUID.randomUUID()}")
                        "Tag retired"
                    }
                }
            }
        }
    }
}
