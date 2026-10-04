package com.astrawms.mobile.core.scan

import java.time.LocalDate
import java.time.YearMonth

/**
 * GS1-128 / DataMatrix element strings as handheld scanners deliver them, the device-side twin of the server's
 * `com.astrawms.common.barcode.Gs1` (ADR-0022): bracketed `(01)…(10)…` or raw with the GS separator and an optional
 * symbology identifier (`]C1`, `]d2`, `]Q3`). Used to fill RF fields from one scan; the server parses again.
 */
object Gs1 {

    const val GS = '\u001D'

    private val FIXED = mapOf("00" to 18, "01" to 14, "02" to 14, "11" to 6, "13" to 6, "15" to 6, "17" to 6)
    private val VARIABLE = mapOf("10" to 20, "21" to 20, "30" to 8, "37" to 8, "400" to 30)

    data class Data(
        val sscc: String?,
        val gtin: String?,
        val contentGtin: String?,
        val lot: String?,
        val expiry: LocalDate?,
        val serial: String?,
        val count: String?,
        val elements: Map<String, String>,
    ) {
        val itemGtin: String? get() = gtin ?: contentGtin
    }

    fun parse(scan: String?): Data? {
        if (scan == null) return null
        var s = scan.trim()
        if (s.startsWith("]")) {
            if (s.length < 3) return null
            s = s.substring(3)
        }
        return try {
            val e = (if (s.startsWith("(")) bracketed(s) else raw(s)) ?: return null
            if (e.isEmpty()) return null
            Data(e["00"], e["01"], e["02"], e["10"], date(e["17"]), e["21"], e["37"] ?: e["30"], e)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    private fun bracketed(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            require(s[i] == '(') { "expected (" }
            val close = s.indexOf(')', i)
            require(close > 0) { "unclosed AI" }
            val ai = s.substring(i + 1, close)
            val next = s.indexOf('(', close)
            val value = s.substring(close + 1, if (next < 0) s.length else next).replace(GS.toString(), "")
            check(ai, value)
            out[ai] = value
            i = if (next < 0) s.length else next
        }
        return out
    }

    private fun raw(s: String): Map<String, String>? {
        if (s.length <= 14 && s.all { it.isDigit() }) return null      // a bare GTIN, not an element string
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == GS) { i++; continue }
            val ai = ai(s, i) ?: throw IllegalArgumentException("unknown AI")
            i += ai.length
            val fixed = FIXED[ai]
            val value: String
            if (fixed != null) {
                require(i + fixed <= s.length) { "short field" }
                value = s.substring(i, i + fixed)
                i += fixed
            } else {
                val end = s.indexOf(GS, i).let { if (it < 0) s.length else it }
                value = s.substring(i, end)
                i = end
            }
            check(ai, value)
            out[ai] = value
        }
        return out
    }

    private fun ai(s: String, i: Int): String? {
        for (len in 2..3) {
            if (i + len > s.length) return null
            val ai = s.substring(i, i + len)
            if (ai in FIXED || ai in VARIABLE) return ai
        }
        return null
    }

    private fun check(ai: String, value: String) {
        val fixed = FIXED[ai]
        val max = VARIABLE[ai]
        require(fixed != null || max != null) { "unsupported AI $ai" }
        if (fixed != null) require(value.length == fixed && value.all { it.isDigit() }) { "AI $ai needs $fixed digits" }
        if (max != null) require(value.isNotEmpty() && value.length <= max) { "AI $ai length" }
        if (ai == "00" || ai == "01" || ai == "02") {
            require(com.astrawms.mobile.core.rfid.Epc.checkDigit(value.dropLast(1)) == value.last() - '0') { "check digit" }
        }
        if (ai == "30" || ai == "37") require(value.all { it.isDigit() }) { "AI $ai must be numeric" }
    }

    /** YYMMDD; DD 00 is the last day of the month; the century follows the GS1 sliding window. */
    private fun date(v: String?): LocalDate? {
        if (v == null) return null
        val yy = v.substring(0, 2).toInt()
        val mm = v.substring(2, 4).toInt()
        val dd = v.substring(4, 6).toInt()
        val current = LocalDate.now().year
        var year = current / 100 * 100 + yy
        val diff = yy - current % 100
        if (diff >= 51) year -= 100 else if (diff <= -50) year += 100
        require(mm in 1..12) { "month" }
        return try {
            if (dd == 0) YearMonth.of(year, mm).atEndOfMonth() else LocalDate.of(year, mm, dd)
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("date", e)
        }
    }
}
