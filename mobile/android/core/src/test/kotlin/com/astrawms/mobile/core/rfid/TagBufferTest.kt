package com.astrawms.mobile.core.rfid

import kotlin.test.Test
import kotlin.test.assertEquals

class TagBufferTest {

    @Test
    fun oneEntryPerTagWhateverTheFormat() {
        val b = TagBuffer()
        val unit1 = Epc.sgtin96("00614141123452", 7, 1).hex
        val unit2 = Epc.sgtin96("00614141123452", 7, 2).hex
        assertEquals(2, b.add(listOf(TagRead(unit1, -60, at = 1), TagRead(unit1.lowercase(), -40, at = 2), TagRead(unit2))))
        assertEquals(0, b.add(TagRead("urn:epc:id:sgtin:0614141.012345.1", -70, at = 3)))
        b.add(TagRead("3174257BF4499602D2000000"))
        b.add(TagRead("E2801160600002000000000A"))
        assertEquals(4, b.size)
        val first = b.tags.value.first()
        assertEquals(3, first.count)
        assertEquals(-40, first.bestRssi)
        assertEquals(-70, first.lastRssi)
        assertEquals(mapOf("00614141123452" to listOf("1", "2")), b.unitsByGtin())
        assertEquals(listOf("106141412345678908"), b.ssccs())
        assertEquals(listOf(unit1, unit2, "3174257BF4499602D2000000", "E2801160600002000000000A"), b.epcs())
        b.clear()
        assertEquals(0, b.size)
    }

    @Test
    fun proximityScale() {
        assertEquals(0, proximity(-90))
        assertEquals(60, proximity(-50))
        assertEquals(100, proximity(-20))
        assertEquals(null, proximity(null))
    }
}
