package com.astrawms.mobile.core.scan

import com.astrawms.mobile.core.rfid.Epc
import com.astrawms.mobile.core.rfid.TagRead

/** What a scanner wedge (DataWedge intent, keyboard wedge, NFC) delivered: a barcode, or one or more RFID tags. */
sealed interface ScanInput {
    data class Barcode(val text: String, val symbology: String? = null) : ScanInput
    data class Tags(val reads: List<TagRead>) : ScanInput
}

object ScanClassifier {

    private val SEPARATORS = Regex("[\\r\\n,;|\\t]+")

    /**
     * Classifies a wedge payload. Wedges deliver several tags of one trigger pull in one string, one per line (or
     * separated by commas / semicolons / tabs): when every part is EPC-shaped it is a tag read, otherwise it is one
     * barcode (GS1 element strings contain no such separators; their FNC1 is the GS character). A label type that
     * names RFID forces tags.
     */
    fun classify(payload: String, labelType: String? = null, now: Long = System.currentTimeMillis()): ScanInput? {
        val text = payload.trim { it.isWhitespace() && it != '\u001D' }
        if (text.isEmpty()) return null
        val parts = text.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        val rfidLabel = labelType?.contains("RFID", ignoreCase = true) == true
        if (parts.isNotEmpty() && (rfidLabel || parts.all { Epc.looksLikeEpc(it) && isTagShaped(it) })) {
            return ScanInput.Tags(parts.map { TagRead(it, at = now) })
        }
        return ScanInput.Barcode(text, labelType)
    }

    /**
     * A barcode of 24+ hex digits (some item numbers are numeric) is taken for an EPC only when it decodes as one or
     * holds hex letters; a purely numeric 24+ digit code that is no EPC stays a barcode.
     */
    private fun isTagShaped(s: String): Boolean {
        if (s.lowercase().startsWith("urn:epc:")) return true
        val hex = Epc.hexOf(s) ?: return false
        return Epc.parse(s) != null || hex.any { it in 'A'..'F' }
    }
}
