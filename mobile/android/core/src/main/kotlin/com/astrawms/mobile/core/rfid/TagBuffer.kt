package com.astrawms.mobile.core.rfid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One tag report from a reader: the EPC as the reader gave it, with signal strength when the reader reports it. */
data class TagRead(val epc: String, val rssi: Int? = null, val antenna: Int? = null, val at: Long = System.currentTimeMillis())

/** A tag seen during a read session: how often, how strong at best and most recently, and what it decodes to. */
data class SeenTag(
    /** One per tag: the EPC pure identity URI when decoded, else the hex or raw read. */
    val key: String,
    val raw: String,
    val decoded: Epc.Tag?,
    val count: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val bestRssi: Int?,
    val lastRssi: Int?,
)

/**
 * The tags of one read session (a pallet, a bin, a dock door). Readers report each tag many times per second; the
 * buffer keeps one entry per EPC so a count never counts a tag twice. Reads that are not EPCs are kept by their raw
 * text (some readers deliver a TID or a vendor encoding) so the server can still resolve commissioned tags.
 * Thread-safe; [tags] is observable by the UI.
 */
class TagBuffer(private val maxTags: Int = 5000) {

    private val lock = Any()
    private val byKey = LinkedHashMap<String, SeenTag>()
    private val state = MutableStateFlow<List<SeenTag>>(emptyList())

    /** The tags seen, in the order they were first seen. */
    val tags: StateFlow<List<SeenTag>> = state.asStateFlow()

    /** Adds reads; returns how many tags were new. */
    fun add(reads: Collection<TagRead>): Int {
        var added = 0
        synchronized(lock) {
            for (r in reads) {
                val raw = r.epc.trim()
                if (raw.isEmpty()) continue
                val decoded = Epc.parse(raw)
                // the identity, not the bits: the same tag read as hex (with its filter value) or as a pure identity
                // URI (without one) is one tag
                val key = decoded?.uri ?: Epc.hexOf(raw) ?: raw
                val old = byKey[key]
                if (old == null) {
                    if (byKey.size >= maxTags) continue
                    byKey[key] = SeenTag(key, raw, decoded, 1, r.at, r.at, r.rssi, r.rssi)
                    added++
                } else {
                    byKey[key] = old.copy(
                        count = old.count + 1,
                        lastSeen = maxOf(old.lastSeen, r.at),
                        bestRssi = listOfNotNull(old.bestRssi, r.rssi).maxOrNull(),
                        lastRssi = r.rssi ?: old.lastRssi,
                    )
                }
            }
            state.value = byKey.values.toList()
        }
        return added
    }

    fun add(read: TagRead): Int = add(listOf(read))

    fun clear() {
        synchronized(lock) {
            byKey.clear()
            state.value = emptyList()
        }
    }

    /** The EPCs to send to the server: hex as first read for decoded tags, the raw read otherwise. */
    fun epcs(): List<String> = synchronized(lock) { byKey.values.map { it.decoded?.hex ?: Epc.hexOf(it.raw) ?: it.raw } }

    val size: Int get() = synchronized(lock) { byKey.size }

    /**
     * Unit tags per GTIN with their serials, for receive and pick: N tags of one GTIN are N units of that GTIN.
     * Only SGTIN tags count; LPN, location and asset tags are left out.
     */
    fun unitsByGtin(): Map<String, List<String>> = synchronized(lock) {
        byKey.values.mapNotNull { it.decoded }
            .filter { it.scheme == Epc.Scheme.SGTIN && it.gtin != null }
            .groupBy({ it.gtin!! }, { it.serial ?: "" })
    }

    /** SSCCs of the logistic-unit (pallet / case) tags read. */
    fun ssccs(): List<String> = synchronized(lock) {
        byKey.values.mapNotNull { it.decoded?.sscc }.distinct()
    }
}

/**
 * Proximity for "find this tag": the reader's RSSI (dBm, typically -80 far .. -30 touching) as 0..100. Readers that
 * do not report RSSI give null and the UI shows only "seen".
 */
fun proximity(rssi: Int?): Int? = rssi?.let { ((it + 80) * 2).coerceIn(0, 100) }
