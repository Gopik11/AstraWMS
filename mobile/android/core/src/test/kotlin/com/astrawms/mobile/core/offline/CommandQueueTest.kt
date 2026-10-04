package com.astrawms.mobile.core.offline

import com.astrawms.mobile.core.api.ApiException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandQueueTest {

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String?>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { map[key] = value }
    }

    private val task = "/api/v1/sites/DC1/tasks/0b6c2d34-5a0f-4c8e-9d5e-1f2a3b4c5d6e"

    @Test
    fun offlineCommandsWaitInOrderAndReplay() = runTest {
        val store = MapStore()
        val posted = mutableListOf<String>()
        var online = false
        val flagged = mutableListOf<String>()
        val q = CommandQueue(store, post = { c ->
            if (!online) throw IOException("offline")
            if (c.label == "refused") throw ApiException(422, "TSK_WRONG_LPN", "LPN moved")
            posted += c.label
        }, flagConflict = { path, detail -> flagged += "$path|$detail" })

        val first = q.submit("$task/confirm", "{}", "first") { throw IOException("offline") }
        assertIs<Outcome.Queued>(first)
        // while one waits, the next is queued without trying, so the order holds
        var tried = false
        assertIs<Outcome.Queued>(q.submit("$task/pick", "{}", "refused") { tried = true })
        assertEquals(false, tried)
        q.submit("$task/count", "{}", "third") { error("not called") }
        assertEquals(3, q.pending.size)

        assertEquals(SyncResult(0, 0), q.sync())                   // still offline
        online = true
        assertEquals(SyncResult(2, 1), q.sync())
        assertEquals(listOf("first", "third"), posted)
        assertEquals("TSK_WRONG_LPN: LPN moved", q.failed.single().error)
        assertEquals(listOf("$task|refused: TSK_WRONG_LPN: LPN moved"), flagged)

        // the queue survives a restart of the app
        val again = CommandQueue(store, post = {})
        assertEquals(1, again.failed.size)
        again.discard(again.failed.single().id)
        assertEquals(0, again.commands.value.size)
    }

    @Test
    fun onlineCommandsRunAtOnceAndServerErrorsReachTheOperator() = runTest {
        val q = CommandQueue(MapStore(), post = {})
        assertEquals(Outcome.Done(42), q.submit("/x", "{}", "x") { 42 })
        val e = runCatching { q.submit("/x", "{}", "x") { throw ApiException(422, "C", "no") } }.exceptionOrNull()
        assertIs<ApiException>(e)
        assertEquals(0, q.commands.value.size)
    }

    @Test
    fun downloadedTasks() {
        val q = CommandQueue(MapStore(), post = {})
        q.saveOfflineTasks("DC1", listOf(com.astrawms.mobile.core.api.Task("t1", "PICK", "ASSIGNED"),
            com.astrawms.mobile.core.api.Task("t2", "COUNT", "ASSIGNED")))
        q.dropOfflineTask("DC1", "t1")
        assertEquals(listOf("t2"), q.offlineTasks("DC1").map { it.id })
        assertEquals(emptyList(), q.offlineTasks("ST01"))
    }
}
