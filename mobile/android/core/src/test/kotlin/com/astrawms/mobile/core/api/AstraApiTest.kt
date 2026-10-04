package com.astrawms.mobile.core.api

import com.astrawms.mobile.core.rfid.RfidAssist
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AstraApiTest {

    private val server = MockWebServer().apply { start() }
    private val api = AstraApi({ server.url("/").toString() }, { "tok" })

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun nextTaskAndNoWork() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"t1","taskType":"RECEIVE","status":"ASSIGNED","priority":40,"docNo":"1800009","receiveKind":"ASN",
             "expectedLines":[{"lineRef":"000010","itemNo":"SKU-1","qty":24,"uom":"EA"}],"scans":0,"unknown":true}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val t = api.nextTask("DC1")!!
        assertEquals("SKU-1", t.expectedLines!!.single().itemNo)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/sites/DC1/tasks/next", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertNull(api.nextTask("DC1"))
    }

    @Test
    fun problemsBecomeApiExceptions() = runTest {
        server.enqueue(MockResponse().setResponseCode(422)
            .setBody("""{"title":"Unprocessable","status":422,"code":"TSK_WRONG_LPN","detail":"Scanned LPN X but the task is for Y"}"""))
        val e = assertFailsWith<ApiException> { api.confirmPutaway("DC1", "t1", PutawayConfirm("X", "A-01", "11")) }
        assertEquals("TSK_WRONG_LPN", e.code)
        assertEquals("""{"lpnId":"X","locationId":"A-01","checkDigit":"11"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun readsRetryWhileTheGatewayRestartsAService() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        server.enqueue(MockResponse().setBody("""{"epc":"E2","scheme":"RAW","siteId":"DC1","status":"ACTIVE"}"""))
        assertEquals("ACTIVE", api.tag("DC1", "E2").status)
    }

    @Test
    fun resolveAndTally() = runTest {
        server.enqueue(MockResponse().setBody("""[
            {"read":"a","epc":"A","kind":"ITEM","ownerId":"ACME","itemNo":"SKU-1","uom":"EA","baseQty":1,"baseUom":"EA","serialTracked":false},
            {"read":"b","epc":"B","kind":"ITEM","ownerId":"ACME","itemNo":"SKU-1","uom":"CS","baseQty":12,"baseUom":"EA","serialTracked":false},
            {"read":"c","epc":"C","kind":"ITEM","ownerId":"ACME","itemNo":"SKU-S","baseQty":1,"serialTracked":true,"serialNo":"1001"},
            {"read":"d","epc":"D","kind":"LPN","lpnId":"106141412345678908","problem":"LPN_UNKNOWN"},
            {"read":"e","kind":"UNKNOWN","problem":"NOT_AN_EPC"}]"""))
        val r = api.resolve("DC1", listOf("a", "b", "c", "d", "e"))
        assertEquals("""{"reads":["a","b","c","d","e"]}""", server.takeRequest().body.readUtf8())
        assertEquals(13.0, RfidAssist.tally(r, "SKU-1").qty)
        assertEquals("EA", RfidAssist.tally(r, "SKU-1").uom)
        assertEquals(listOf("1001"), RfidAssist.tally(r, "SKU-S").serials)
        assertEquals("106141412345678908", RfidAssist.singleLpn(r))
        assertEquals(listOf("SKU-1" to 2, "SKU-S" to 1), RfidAssist.items(r))
    }

    @Test
    fun reconcileToCountLines() = runTest {
        server.enqueue(MockResponse().setBody("""{"locationId":"A-01-01","reads":3,"tags":3,
            "lpns":[{"lpnId":"P1","result":"FOUND","systemLocation":"A-01-01"}],
            "lines":[{"ownerId":"ACME","itemNo":"SKU-1","lotNo":"","lpnId":"","expectedQty":3,"readQty":2,"variance":-1}],
            "countLines":[{"ownerId":"ACME","itemNo":"SKU-1","lotNo":null,"lpnId":"P1","qty":24},
                          {"ownerId":"ACME","itemNo":"SKU-1","qty":2}]}"""))
        val r = api.reconcile("DC1", "A-01-01", listOf("x"))
        assertEquals("/api/v1/sites/DC1/inventory/rfid/locations/A-01-01/reconcile", server.takeRequest().path)
        assertEquals(listOf(CountedLine("ACME", "SKU-1", null, "P1", 24.0), CountedLine("ACME", "SKU-1", null, null, 2.0)),
            RfidAssist.countLines(r))
    }

    @Test
    fun receiptProgressFromBothDocumentShapes() = runTest {
        server.enqueue(MockResponse().setBody("""{"lines":[{"erpLineRef":"10","itemNo":"A","qtyExpected":5,"qtyReceived":2,"uom":"EA"}]}"""))
        server.enqueue(MockResponse().setBody("""{"lines":[{"erp_line_ref":"1","item_no":"B","qty_expected":"1.000","qty_received":0,"uom":"EA"}]}"""))
        assertEquals(LineProgress("10", "A", 5.0, 2.0, "EA"), api.receiveProgress("DC1", "1800009", rma = false).single())
        assertEquals(LineProgress("1", "B", 1.0, 0.0, "EA"), api.receiveProgress("DC1", "RMA 1", rma = true).single())
        server.takeRequest()
        assertEquals("/api/v1/sites/DC1/returns/RMA%201", server.takeRequest().path)
    }
}
