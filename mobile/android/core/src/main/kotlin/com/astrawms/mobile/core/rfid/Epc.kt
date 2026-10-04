package com.astrawms.mobile.core.rfid

import java.math.BigInteger

/**
 * EPC of a UHF RFID tag per the GS1 EPC Tag Data Standard, decoded on the device (ADR-0027). The same rules as the
 * server's `com.astrawms.common.rfid.Epc`: SGTIN-96, SSCC-96, SGLN-96, GRAI-96, GIAI-96 and GID-96 from the EPC bank
 * in hex or from an EPC URI; SGTIN-96 and SSCC-96 can be encoded. The server stays authoritative (it resolves GTINs
 * to items and SSCCs to LPNs); the device decodes for instant feedback and to group reads while offline.
 */
object Epc {

    enum class Scheme { SGTIN, SSCC, SGLN, GRAI, GIAI, GID }

    /** A decoded tag. [hex] is the canonical 96-bit EPC; [gtin] is a GTIN-14, [sscc] an SSCC-18, [gln] a GLN-13. */
    data class Tag(
        val scheme: Scheme,
        val hex: String,
        val filter: Int,
        val companyPrefix: String?,
        val gtin: String? = null,
        val serial: String? = null,
        val sscc: String? = null,
        val gln: String? = null,
        val extension: String? = null,
        val assetType: String? = null,
        val uri: String,
    )

    const val SGTIN_96_MAX_SERIAL: Long = (1L shl 38) - 1

    private const val SGTIN_96 = 0x30
    private const val SSCC_96 = 0x31
    private const val SGLN_96 = 0x32
    private const val GRAI_96 = 0x33
    private const val GIAI_96 = 0x34
    private const val GID_96 = 0x35

    /** {company prefix bits, company prefix digits, reference bits, reference digits} per partition value. */
    private val SGTIN_PARTITIONS = arrayOf(
        intArrayOf(40, 12, 4, 1), intArrayOf(37, 11, 7, 2), intArrayOf(34, 10, 10, 3), intArrayOf(30, 9, 14, 4),
        intArrayOf(27, 8, 17, 5), intArrayOf(24, 7, 20, 6), intArrayOf(20, 6, 24, 7),
    )
    private val SSCC_PARTITIONS = arrayOf(
        intArrayOf(40, 12, 18, 5), intArrayOf(37, 11, 21, 6), intArrayOf(34, 10, 24, 7), intArrayOf(30, 9, 28, 8),
        intArrayOf(27, 8, 31, 9), intArrayOf(24, 7, 34, 10), intArrayOf(20, 6, 38, 11),
    )
    private val SGLN_PARTITIONS = arrayOf(
        intArrayOf(40, 12, 1, 0), intArrayOf(37, 11, 4, 1), intArrayOf(34, 10, 7, 2), intArrayOf(30, 9, 11, 3),
        intArrayOf(27, 8, 14, 4), intArrayOf(24, 7, 17, 5), intArrayOf(20, 6, 21, 6),
    )
    private val GRAI_PARTITIONS = arrayOf(
        intArrayOf(40, 12, 4, 0), intArrayOf(37, 11, 7, 1), intArrayOf(34, 10, 10, 2), intArrayOf(30, 9, 14, 3),
        intArrayOf(27, 8, 17, 4), intArrayOf(24, 7, 20, 5), intArrayOf(20, 6, 24, 6),
    )
    private val GIAI_PARTITIONS = arrayOf(
        intArrayOf(40, 12, 42, 12), intArrayOf(37, 11, 45, 13), intArrayOf(34, 10, 48, 14), intArrayOf(30, 9, 52, 15),
        intArrayOf(27, 8, 55, 16), intArrayOf(24, 7, 58, 17), intArrayOf(20, 6, 62, 18),
    )

    /** The hex digits of a read (no 0x, spaces, dashes or colons), upper case; null when it is not hex. */
    fun hexOf(read: String): String? {
        var s = read.trim()
        if (s.startsWith("0x", ignoreCase = true)) s = s.substring(2)
        s = s.replace(Regex("[\\s:-]"), "")
        if (s.isEmpty() || !s.all { Character.digit(it, 16) >= 0 }) return null
        return s.uppercase()
    }

    /** Whether a scan is shaped like an EPC (hex EPC bank of 96+ bits, or an EPC URI), without decoding it. */
    fun looksLikeEpc(scan: String): Boolean {
        val s = scan.trim().lowercase()
        if (s.startsWith("urn:epc:id:") || s.startsWith("urn:epc:tag:")) return true
        val hex = hexOf(scan) ?: return false
        return hex.length >= 24 && hex.length % 4 == 0
    }

    /** Decodes a read; null when it is not a supported, well-formed EPC. */
    fun parse(read: String?): Tag? {
        if (read.isNullOrBlank()) return null
        return try {
            val s = read.trim()
            val lower = s.lowercase()
            when {
                lower.startsWith("urn:epc:id:") -> fromPureIdentity(s.substring("urn:epc:id:".length), 0)
                lower.startsWith("urn:epc:tag:") -> fromTagUri(s.substring("urn:epc:tag:".length))
                else -> {
                    val hex = hexOf(s) ?: return null
                    if (hex.length < 24 || hex.length % 4 != 0) return null
                    if (hex.length > 24 && hex.substring(24).any { it != '0' }) return null
                    decode(hex.substring(0, 24))
                }
            }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ArithmeticException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    // ------------------------------------------------------------------ binary

    private fun decode(hex: String): Tag {
        val b = BitReader(BigInteger(hex, 16), 96)
        return when (val header = b.take(8).toInt()) {
            SGTIN_96 -> {
                val filter = b.take(3).toInt()
                val p = SGTIN_PARTITIONS[partition(b)]
                val company = digits(b.take(p[0]), p[1])
                val itemRef = digits(b.take(p[2]), p[3])
                sgtin(filter, company, itemRef, b.take(38).toString())
            }
            SSCC_96 -> {
                val filter = b.take(3).toInt()
                val p = SSCC_PARTITIONS[partition(b)]
                val company = digits(b.take(p[0]), p[1])
                val serialRef = digits(b.take(p[2]), p[3])
                require(b.take(24) == 0L) { "SSCC-96 reserved bits" }
                sscc(filter, company, serialRef)
            }
            SGLN_96 -> {
                val filter = b.take(3).toInt()
                val p = SGLN_PARTITIONS[partition(b)]
                val company = digits(b.take(p[0]), p[1])
                val locRef = if (p[3] == 0) zeroRef(b.take(p[2])) else digits(b.take(p[2]), p[3])
                sgln(filter, company, locRef, b.take(41).toString())
            }
            GRAI_96 -> {
                val filter = b.take(3).toInt()
                val p = GRAI_PARTITIONS[partition(b)]
                val company = digits(b.take(p[0]), p[1])
                val assetType = if (p[3] == 0) zeroRef(b.take(p[2])) else digits(b.take(p[2]), p[3])
                grai(filter, company, assetType, b.take(38).toString())
            }
            GIAI_96 -> {
                val filter = b.take(3).toInt()
                val p = GIAI_PARTITIONS[partition(b)]
                val company = digits(b.take(p[0]), p[1])
                val asset = b.takeBig(p[2])
                require(asset.toString().length <= p[3]) { "GIAI-96 asset reference too long" }
                Tag(Scheme.GIAI, hex.uppercase(), filter, company, serial = asset.toString(),
                    uri = "urn:epc:id:giai:$company.$asset")
            }
            GID_96 -> {
                val manager = b.take(28)
                val objectClass = b.take(24)
                val serial = b.take(36)
                Tag(Scheme.GID, hex.uppercase(), 0, null, serial = serial.toString(), assetType = objectClass.toString(),
                    uri = "urn:epc:id:gid:$manager.$objectClass.$serial")
            }
            else -> throw IllegalArgumentException("unsupported EPC header $header")
        }
    }

    private fun partition(b: BitReader): Int {
        val p = b.take(3).toInt()
        require(p <= 6) { "partition $p" }
        return p
    }

    private fun zeroRef(value: Long): String {
        require(value == 0L) { "non-zero empty reference" }
        return ""
    }

    // ------------------------------------------------------------------ building tags

    private fun sgtin(filter: Int, company: String, itemRef: String, serial: String): Tag {
        val body = itemRef[0] + company + itemRef.substring(1)
        return Tag(Scheme.SGTIN, encodeSgtin(filter, company, itemRef, serial.toLong()), filter, company,
            gtin = body + checkDigit(body), serial = serial, uri = "urn:epc:id:sgtin:$company.$itemRef.$serial")
    }

    private fun sscc(filter: Int, company: String, serialRef: String): Tag {
        val body = serialRef[0] + company + serialRef.substring(1)
        return Tag(Scheme.SSCC, encodeSscc(filter, company, serialRef), filter, company,
            sscc = body + checkDigit(body), uri = "urn:epc:id:sscc:$company.$serialRef")
    }

    private fun sgln(filter: Int, company: String, locRef: String, ext: String): Tag {
        val part = partitionFor(SGLN_PARTITIONS, company)
        val p = SGLN_PARTITIONS[part]
        val hex = BitWriter().put(SGLN_96.toLong(), 8).put(filter.toLong(), 3).put(part.toLong(), 3)
            .put(company.toLong(), p[0]).put(if (locRef.isEmpty()) 0 else locRef.toLong(), p[2]).put(ext.toLong(), 41).hex()
        val body = company + locRef
        return Tag(Scheme.SGLN, hex, filter, company, gln = body + checkDigit(body), extension = ext,
            uri = "urn:epc:id:sgln:$company.$locRef.$ext")
    }

    private fun grai(filter: Int, company: String, assetType: String, serial: String): Tag {
        val part = partitionFor(GRAI_PARTITIONS, company)
        val p = GRAI_PARTITIONS[part]
        val hex = BitWriter().put(GRAI_96.toLong(), 8).put(filter.toLong(), 3).put(part.toLong(), 3)
            .put(company.toLong(), p[0]).put(if (assetType.isEmpty()) 0 else assetType.toLong(), p[2])
            .put(serial.toLong(), 38).hex()
        return Tag(Scheme.GRAI, hex, filter, company, serial = serial, assetType = assetType,
            uri = "urn:epc:id:grai:$company.$assetType.$serial")
    }

    // ------------------------------------------------------------------ URIs

    private fun fromPureIdentity(rest: String, filter: Int): Tag {
        val colon = rest.indexOf(':')
        require(colon > 0) { "no scheme" }
        val scheme = rest.substring(0, colon).lowercase()
        val f = rest.substring(colon + 1).split(".")
        require(f.all { part -> part.all { it.isDigit() } }) { "EPC URI fields must be numeric" }
        return when (scheme) {
            "sgtin" -> {
                need(f, 3); checkSplit(SGTIN_PARTITIONS, f[0], f[1])
                sgtin(filter, f[0], f[1], numericSerial(f[2]))
            }
            "sscc" -> {
                need(f, 2); checkSplit(SSCC_PARTITIONS, f[0], f[1])
                sscc(filter, f[0], f[1])
            }
            "sgln" -> {
                need(f, 3); checkSplit(SGLN_PARTITIONS, f[0], f[1])
                sgln(filter, f[0], f[1], numericSerial(f[2]))
            }
            "grai" -> {
                need(f, 3); checkSplit(GRAI_PARTITIONS, f[0], f[1])
                grai(filter, f[0], f[1], numericSerial(f[2]))
            }
            else -> throw IllegalArgumentException("unsupported EPC URI scheme $scheme")
        }
    }

    private fun fromTagUri(rest: String): Tag {
        val colon = rest.indexOf(':')
        require(colon > 0) { "no scheme" }
        val scheme = rest.substring(0, colon).lowercase()
        require(scheme.endsWith("-96")) { "only 96-bit tag URIs are supported" }
        val body = rest.substring(colon + 1)
        val dot = body.indexOf('.')
        val filter = body.substring(0, dot).toInt()
        require(filter in 0..7) { "filter $filter" }
        return fromPureIdentity(scheme.removeSuffix("-96") + ":" + body.substring(dot + 1), filter)
    }

    private fun need(f: List<String>, n: Int) = require(f.size == n) { "expected $n fields" }

    private fun numericSerial(s: String): String {
        require(s.isNotEmpty() && !(s.length > 1 && s[0] == '0')) { "a 96-bit serial is a number without leading zeros" }
        return s
    }

    private fun checkSplit(table: Array<IntArray>, company: String, reference: String) {
        val p = table[partitionFor(table, company)]
        require(reference.length == p[3]) { "reference must have ${p[3]} digits" }
    }

    private fun partitionFor(table: Array<IntArray>, company: String): Int {
        val i = table.indexOfFirst { it[1] == company.length }
        require(i >= 0) { "GS1 company prefix must have 6-12 digits" }
        return i
    }

    // ------------------------------------------------------------------ encoding

    /** SGTIN-96 for a GTIN (8-14 digits incl. check digit), the GS1 company prefix length and a numeric serial. */
    fun sgtin96(gtin: String, companyPrefixLength: Int, serial: Long, filter: Int = 1): Tag {
        require(gtin.matches(Regex("\\d{8}|\\d{12,14}"))) { "GTIN must be 8, 12, 13 or 14 digits" }
        val g14 = gtin.padStart(14, '0')
        require(checkDigit(g14.substring(0, 13)) == g14[13] - '0') { "GTIN $gtin has an invalid check digit" }
        require(serial in 0..SGTIN_96_MAX_SERIAL) { "SGTIN-96 serial must be 0..$SGTIN_96_MAX_SERIAL" }
        require(filter in 0..7) { "filter must be 0..7" }
        require(companyPrefixLength in 6..12) { "GS1 company prefix must have 6-12 digits" }
        val company = g14.substring(1, 1 + companyPrefixLength)
        val itemRef = g14[0] + g14.substring(1 + companyPrefixLength, 13)
        return sgtin(filter, company, itemRef, serial.toString())
    }

    /** SSCC-96 for an 18-digit SSCC (check digit included). */
    fun sscc96(sscc: String, companyPrefixLength: Int, filter: Int = 0): Tag {
        require(sscc.matches(Regex("\\d{18}")) && checkDigit(sscc.substring(0, 17)) == sscc[17] - '0') {
            "SSCC must be 18 digits with a valid check digit"
        }
        require(filter in 0..7) { "filter must be 0..7" }
        require(companyPrefixLength in 6..12) { "GS1 company prefix must have 6-12 digits" }
        val company = sscc.substring(1, 1 + companyPrefixLength)
        val serialRef = sscc[0] + sscc.substring(1 + companyPrefixLength, 17)
        return sscc(filter, company, serialRef)
    }

    private fun encodeSgtin(filter: Int, company: String, itemRef: String, serial: Long): String {
        val part = partitionFor(SGTIN_PARTITIONS, company)
        val p = SGTIN_PARTITIONS[part]
        require(itemRef.length == p[3]) { "item reference must have ${p[3]} digits" }
        require(serial in 0..SGTIN_96_MAX_SERIAL) { "SGTIN-96 serial out of range" }
        return BitWriter().put(SGTIN_96.toLong(), 8).put(filter.toLong(), 3).put(part.toLong(), 3)
            .put(company.toLong(), p[0]).put(itemRef.toLong(), p[2]).put(serial, 38).hex()
    }

    private fun encodeSscc(filter: Int, company: String, serialRef: String): String {
        val part = partitionFor(SSCC_PARTITIONS, company)
        val p = SSCC_PARTITIONS[part]
        require(serialRef.length == p[3]) { "serial reference must have ${p[3]} digits" }
        return BitWriter().put(SSCC_96.toLong(), 8).put(filter.toLong(), 3).put(part.toLong(), 3)
            .put(company.toLong(), p[0]).put(serialRef.toLong(), p[2]).put(0, 24).hex()
    }

    // ------------------------------------------------------------------ helpers

    private fun digits(value: Long, n: Int): String {
        val s = value.toString()
        require(s.length <= n) { "value $value exceeds $n digits" }
        return s.padStart(n, '0')
    }

    /** GS1 mod-10 check digit for the digits before it. */
    fun checkDigit(body: String): Int {
        var sum = 0
        for (k in body.indices) {
            val d = body[body.length - 1 - k] - '0'
            sum += if (k % 2 == 0) d * 3 else d
        }
        return (10 - sum % 10) % 10
    }

    private class BitReader(private val value: BigInteger, private var remaining: Int) {
        fun take(n: Int): Long = takeBig(n).longValueExact()

        fun takeBig(n: Int): BigInteger {
            remaining -= n
            return value.shiftRight(remaining).and(BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE))
        }
    }

    private class BitWriter {
        private var value = BigInteger.ZERO
        private var written = 0

        fun put(v: Long, n: Int): BitWriter {
            require(v >= 0 && (n >= 63 || v < (1L shl n))) { "value $v does not fit $n bits" }
            value = value.shiftLeft(n).or(BigInteger.valueOf(v))
            written += n
            return this
        }

        fun hex(): String {
            check(written == 96) { "EPC is $written bits" }
            return value.toString(16).uppercase().padStart(24, '0')
        }
    }
}
