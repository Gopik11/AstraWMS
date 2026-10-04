package com.astrawms.mobile.core.rfid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same vectors as the server's EpcTest (GS1 EPC Tag Data Standard examples), so device and server agree. */
class EpcTest {

    private val sgtinHex = "3074257BF7194E4000001A85"

    @Test
    fun decodesSgtin96() {
        val t = Epc.parse(sgtinHex)!!
        assertEquals(Epc.Scheme.SGTIN, t.scheme)
        assertEquals(3, t.filter)
        assertEquals("80614141123458", t.gtin)
        assertEquals("6789", t.serial)
        assertEquals("urn:epc:id:sgtin:0614141.812345.6789", t.uri)
    }

    @Test
    fun readerFormatsAndUris() {
        assertEquals(sgtinHex, Epc.parse("0x" + sgtinHex.lowercase())!!.hex)
        assertEquals(sgtinHex, Epc.parse("3074 257B F719 4E40 0000 1A85")!!.hex)
        assertEquals(sgtinHex, Epc.parse(sgtinHex + "0000")!!.hex)
        assertNull(Epc.parse(sgtinHex + "00A0"))
        assertEquals(sgtinHex, Epc.parse("urn:epc:tag:sgtin-96:3.0614141.812345.6789")!!.hex)
    }

    @Test
    fun ssccAndEncoding() {
        val t = Epc.parse("urn:epc:tag:sscc-96:3.0614141.1234567890")!!
        assertEquals("106141412345678908", t.sscc)
        assertEquals("3174257BF4499602D2000000", t.hex)
        assertEquals("106141412345678908", Epc.parse(t.hex)!!.sscc)
        assertEquals(sgtinHex, Epc.sgtin96("80614141123458", 7, 6789, 3).hex)
        assertEquals("3174257BF4499602D2000000", Epc.sscc96("106141412345678908", 7, 3).hex)
        assertFailsWith<IllegalArgumentException> { Epc.sgtin96("80614141123459", 7, 1) }
        assertFailsWith<IllegalArgumentException> { Epc.sgtin96("80614141123458", 7, Epc.SGTIN_96_MAX_SERIAL + 1) }
    }

    @Test
    fun otherSchemesAndRejects() {
        val sgln = Epc.parse("urn:epc:tag:sgln-96:3.0614141.12345.400")!!
        assertEquals("0614141123452", sgln.gln)
        assertEquals("urn:epc:id:sgln:0614141.12345.400", Epc.parse(sgln.hex)!!.uri)
        assertEquals("urn:epc:id:gid:1.2.3", Epc.parse("350000001000002000000003")!!.uri)
        assertNull(Epc.parse("SKU-100"))
        assertNull(Epc.parse("E28011606000020000000001"))
        assertNull(Epc.parse("3077257BF7194E4000001A85"))
        assertNull(Epc.parse("urn:epc:id:sgtin:0614141.812345.06789"))
        assertTrue(Epc.looksLikeEpc("E28011606000020000000001"))
    }
}
