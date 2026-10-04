package com.astrawms.mobile.core.rfid

import com.astrawms.mobile.core.api.CountedLine
import com.astrawms.mobile.core.api.Reconciliation
import com.astrawms.mobile.core.api.Resolved

/**
 * Turns resolved tags into what an RF screen fills in (ADR-0027). RFID only proposes: the operator sees the values
 * and confirms, and the server checks them like any other scan.
 */
object RfidAssist {

    /**
     * Units of one item read: quantity in the item's base unit [uom] (a case tag counts its case size) and the serials
     * of tracked units.
     */
    data class Tally(val qty: Double, val uom: String?, val serials: List<String>, val tags: Int, val problems: List<Resolved>)

    fun tally(resolved: List<Resolved>, itemNo: String, ownerId: String? = null): Tally {
        val units = resolved.filter {
            it.kind == "ITEM" && it.itemNo.equals(itemNo, ignoreCase = true) && (ownerId == null || it.ownerId == ownerId)
        }
        return Tally(
            qty = units.sumOf { it.baseQty ?: 1.0 },
            uom = units.mapNotNull { it.baseUom ?: it.uom }.distinct().singleOrNull(),
            serials = units.filter { it.serialTracked }.mapNotNull { it.serialNo }.distinct(),
            tags = units.size,
            problems = units.filter { it.problem != null },
        )
    }

    /** Items among the reads, most tags first: what a receiver probably holds when no item was chosen yet. */
    fun items(resolved: List<Resolved>): List<Pair<String, Int>> =
        resolved.filter { it.kind == "ITEM" && it.itemNo != null }
            .groupingBy { it.itemNo!! }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** The LPN when exactly one LPN tag was read (a pallet label); null when none or several. */
    fun singleLpn(resolved: List<Resolved>): String? =
        resolved.filter { it.kind == "LPN" && it.lpnId != null }.map { it.lpnId!! }.distinct().singleOrNull()

    /** The location when exactly one bin tag was read. */
    fun singleLocation(resolved: List<Resolved>): String? =
        resolved.filter { it.kind == "LOCATION" && it.locationId != null }.map { it.locationId!! }.distinct().singleOrNull()

    /** The reconciliation's suggested lines as RF count lines (the counter reviews them before submitting). */
    fun countLines(r: Reconciliation): List<CountedLine> =
        r.countLines.map { CountedLine(it.ownerId, it.itemNo, it.lotNo?.ifEmpty { null }, it.lpnId?.ifEmpty { null }, it.qty) }
}
