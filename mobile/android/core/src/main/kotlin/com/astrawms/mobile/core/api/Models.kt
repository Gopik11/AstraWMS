package com.astrawms.mobile.core.api

import kotlinx.serialization.Serializable

// ------------------------------------------------------------------ tasks (task-service, ADR-0008/0019/0020)

@Serializable
data class Content(val ownerId: String? = null, val itemNo: String, val lotNo: String? = null, val qty: Double = 0.0)

@Serializable
data class ExpectedLine(val lineRef: String, val itemNo: String, val qty: Double, val uom: String, val lotNo: String? = null)

@Serializable
data class Task(
    val id: String,
    val taskType: String,
    val status: String,
    val priority: Int = 0,
    val ownerId: String? = null,
    val lpnId: String? = null,
    val fromLocation: String? = null,
    val targetLocation: String? = null,
    val strategy: String? = null,
    val exceptionReason: String? = null,
    val assignedTo: String? = null,
    val orderRef: String? = null,
    val orderLineRef: String? = null,
    val itemNo: String? = null,
    val lotNo: String? = null,
    val qty: Double? = null,
    val uom: String? = null,
    val toLpn: String? = null,
    val qtyPicked: Double? = null,
    val countId: String? = null,
    val countSequence: Int? = null,
    val contents: List<Content>? = null,
    val createdAt: String? = null,
    val suggestedLocation: String? = null,
    val receiveKind: String? = null,
    val docNo: String? = null,
    val partner: String? = null,
    val expectedLines: List<ExpectedLine>? = null,
    val scans: Int = 0,
)

@Serializable
data class PutawayConfirm(val lpnId: String, val locationId: String, val checkDigit: String, val overrideReason: String? = null)

@Serializable
data class PickConfirm(
    val checkDigit: String,
    val item: String,
    val qty: Double,
    val serials: List<String> = emptyList(),
    val shortReason: String? = null,
    val shortAction: String? = null,
)

@Serializable
data class ReceiveScan(
    val scanId: String,
    val docNo: String,
    val itemNo: String,
    val ownerId: String? = null,
    val qty: Double,
    val uom: String,
    val lotNo: String? = null,
    val expiryDate: String? = null,
    val serials: List<String> = emptyList(),
    val lpnId: String? = null,
    val locationId: String,
    val checkDigit: String,
    val conditionGrade: String? = null,
    val disposition: String? = null,
    val returnReason: String? = null,
)

@Serializable
data class ReceiveClose(val shortReasons: Map<String, String> = emptyMap())

@Serializable
data class CheckDigit(val checkDigit: String)

@Serializable
data class CountedLine(val ownerId: String, val itemNo: String, val lotNo: String? = null, val lpnId: String? = null, val qty: Double)

@Serializable
data class CountConfirm(val checkDigit: String, val lines: List<CountedLine>)

@Serializable
data class TaskException(val reason: String, val detail: String? = null)

/** Receipt or RMA progress per line, as the RF receive screen shows it. */
data class LineProgress(val lineRef: String, val itemNo: String, val qtyExpected: Double, val qtyReceived: Double, val uom: String)

// ------------------------------------------------------------------ RFID (inventory-service, ADR-0027)

@Serializable
data class Reads(val reads: List<String>)

@Serializable
data class Sighting(val reads: List<String>, val locationId: String? = null)

/** One read as the server resolved it. kind: ITEM, LPN, LOCATION, ASSET, UNKNOWN. */
@Serializable
data class Resolved(
    val read: String,
    val epc: String? = null,
    val scheme: String? = null,
    val uri: String? = null,
    val kind: String,
    val gtin: String? = null,
    val sscc: String? = null,
    val tagSerial: String? = null,
    val ownerId: String? = null,
    val itemNo: String? = null,
    val uom: String? = null,
    val baseQty: Double? = null,
    /** The item's base unit, in which [baseQty] is given. */
    val baseUom: String? = null,
    val serialTracked: Boolean = false,
    val serialNo: String? = null,
    val lotNo: String? = null,
    val lpnId: String? = null,
    val locationId: String? = null,
    val stockStatus: String? = null,
    val registered: Boolean = false,
    val problem: String? = null,
)

@Serializable
data class LpnCheck(val lpnId: String, val result: String, val systemLocation: String? = null)

@Serializable
data class ReconcileLine(
    val ownerId: String,
    val itemNo: String,
    val lotNo: String = "",
    val lpnId: String = "",
    val expectedQty: Double,
    val readQty: Double,
    val variance: Double,
)

@Serializable
data class SerialRecord(
    val ownerId: String,
    val itemNo: String,
    val serialNo: String,
    val status: String? = null,
    val locationId: String? = null,
    val lpnId: String? = null,
    val lotNo: String? = null,
)

@Serializable
data class CountSuggestion(val ownerId: String, val itemNo: String, val lotNo: String? = null, val lpnId: String? = null, val qty: Double)

@Serializable
data class Reconciliation(
    val locationId: String,
    val reads: Int,
    val tags: Int,
    val lpns: List<LpnCheck> = emptyList(),
    val lines: List<ReconcileLine> = emptyList(),
    val unexpectedUnits: List<Resolved> = emptyList(),
    val serialsNotRead: List<SerialRecord> = emptyList(),
    val unresolved: List<Resolved> = emptyList(),
    val countLines: List<CountSuggestion> = emptyList(),
)

@Serializable
data class Commission(
    val epc: String? = null,
    val ownerId: String? = null,
    val itemNo: String? = null,
    val serialNo: String? = null,
    val lpnId: String? = null,
    val locationId: String? = null,
    val companyPrefixLength: Int? = null,
    val filter: Int? = null,
)

@Serializable
data class TagView(
    val epc: String,
    val scheme: String,
    val uri: String? = null,
    val siteId: String,
    val ownerId: String? = null,
    val itemNo: String? = null,
    val serialNo: String? = null,
    val lpnId: String? = null,
    val locationId: String? = null,
    val status: String,
    val commissionedBy: String? = null,
    val commissionedAt: String? = null,
    val lastSeenAt: String? = null,
    val lastSeenLocation: String? = null,
    val lastSeenBy: String? = null,
)

/** RFC 9457 problem details as the services return them. */
@Serializable
data class Problem(val title: String? = null, val status: Int? = null, val detail: String? = null, val code: String? = null)
