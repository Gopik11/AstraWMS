package com.astrawms.mobile.core.scan

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ScanTest {

    @Test
    fun wedgePayloadsAreTagsOrBarcodes() {
        val tags = assertIs<ScanInput.Tags>(ScanClassifier.classify("3074257BF7194E4000001A85\n3174257BF4499602D2000000\n"))
        assertEquals(2, tags.reads.size)
        assertIs<ScanInput.Tags>(ScanClassifier.classify("E2801160600002000000000A"))
        assertIs<ScanInput.Tags>(ScanClassifier.classify("whatever", labelType = "LABEL-TYPE-RFID"))
        assertEquals("A-01-01", assertIs<ScanInput.Barcode>(ScanClassifier.classify("A-01-01")).text)
        assertIs<ScanInput.Barcode>(ScanClassifier.classify("(01)09506000134352(10)ABC"))
        assertIs<ScanInput.Barcode>(ScanClassifier.classify("123456789012345678901234"))   // numeric, no EPC
        assertNull(ScanClassifier.classify("  "))
    }

    @Test
    fun gs1ElementStrings() {
        val d = Gs1.parse("(01)09506000134352(10)ABC(17)271231(21)S1(37)12")!!
        assertEquals("09506000134352", d.itemGtin)
        assertEquals("ABC", d.lot)
        assertEquals(LocalDate.of(2027, 12, 31), d.expiry)
        assertEquals("S1", d.serial)
        assertEquals("12", d.count)
        val raw = Gs1.parse("]C1" + "00106141412345678908" + "0209506000134352" + "10LOT7" + Gs1.GS + "3724")!!
        assertEquals("106141412345678908", raw.sscc)
        assertEquals("09506000134352", raw.itemGtin)
        assertEquals("LOT7", raw.lot)
        assertEquals("24", raw.count)
        assertNull(Gs1.parse("4006381333931"))
        assertNull(Gs1.parse("(01)09506000134353"))   // check digit
        assertNull(Gs1.parse("SKU-100"))
    }
}
