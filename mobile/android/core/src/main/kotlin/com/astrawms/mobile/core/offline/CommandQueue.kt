package com.astrawms.mobile.core.offline

import com.astrawms.mobile.core.api.ApiException
import com.astrawms.mobile.core.api.Task
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where the device keeps its queue and downloaded tasks (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** An RF command kept on the device: sent later with the same body and the same idempotency key. */
@Serializable
data class QueuedCommand(
    val id: String,
    val path: String,
    val body: String,
    val idempotencyKey: String? = null,
    val label: String,
    val createdAt: Long,
    val status: String = PENDING,
    val error: String? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
    }
}

/** What happened to a command: done online, or kept on the device until the network is back. */
sealed interface Outcome<out T> {
    data class Done<T>(val value: T) : Outcome<T>
    data class Queued(val label: String) : Outcome<Nothing>
}

data class SyncResult(val sent: Int, val failed: Int)

/**
 * Offline RF work for handhelds in places with unreliable network, as the web RF screens do it (ADR-0023):
 * - commands go out at once when possible; without network, or while earlier commands still wait, they are kept on the
 *   device in order and replayed with the same idempotency key, so a command that did reach the server before the
 *   connection dropped is answered, not done twice;
 * - a command the server refuses on replay is kept as FAILED ("needs attention") with the server's reason, and its
 *   task is flagged SYNC_CONFLICT (the server wins, ADR-0024);
 * - downloaded tasks let the operator go on working without network.
 */
class CommandQueue(
    private val store: KeyValueStore,
    private val post: suspend (QueuedCommand) -> Unit,
    private val flagConflict: suspend (taskPath: String, detail: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(QueuedCommand.serializer())
    private val taskSerializer = ListSerializer(Task.serializer())
    private val state = MutableStateFlow(load())
    private val syncing = Mutex()

    val commands: StateFlow<List<QueuedCommand>> = state.asStateFlow()

    val pending: List<QueuedCommand> get() = state.value.filter { it.status == QueuedCommand.PENDING }
    val failed: List<QueuedCommand> get() = state.value.filter { it.status == QueuedCommand.FAILED }

    /**
     * Runs [send] now, unless commands already wait (order matters); on a network failure the command is kept.
     * Errors from the server are thrown to the operator as they are.
     */
    suspend fun <T> submit(path: String, body: String, label: String, idempotencyKey: String? = null,
                           send: suspend () -> T): Outcome<T> {
        if (pending.isEmpty()) {
            try {
                return Outcome.Done(send())
            } catch (e: IOException) {
                // no network: keep it below
            }
        }
        val command = QueuedCommand(UUID.randomUUID().toString(), path, body, idempotencyKey, label, clock())
        mutate { it + command }
        return Outcome.Queued(label)
    }

    /** Sends waiting commands in order; stops at the first network failure, server error or expired sign-in. */
    suspend fun sync(): SyncResult {
        if (!syncing.tryLock()) return SyncResult(0, 0)
        var sent = 0
        var failed = 0
        try {
            for (c in pending) {
                try {
                    post(c)
                    mutate { list -> list.filterNot { it.id == c.id } }
                    sent++
                } catch (e: IOException) {
                    break
                } catch (e: ApiException) {
                    if (e.status >= 500 || e.status == 401) break
                    val reason = "${e.code}: ${e.message}"
                    mutate { list -> list.map { if (it.id == c.id) it.copy(status = QueuedCommand.FAILED, error = reason) else it } }
                    failed++
                    TASK_COMMAND.find(c.path)?.let { m ->
                        try {
                            flagConflict(m.groupValues[1], "${c.label}: $reason")
                        } catch (ignored: Exception) {
                            // the command stays in "needs attention" on the device either way
                        }
                    }
                }
            }
        } finally {
            syncing.unlock()
        }
        return SyncResult(sent, failed)
    }

    fun retry(id: String) = mutate { list -> list.map { if (it.id == id) it.copy(status = QueuedCommand.PENDING, error = null) else it } }

    fun discard(id: String) = mutate { list -> list.filterNot { it.id == id } }

    // ------------------------------------------------------------------ work on the device

    fun offlineTasks(site: String): List<Task> =
        store.get(TASKS + site)?.let { runCatching { json.decodeFromString(taskSerializer, it) }.getOrNull() } ?: emptyList()

    fun saveOfflineTasks(site: String, tasks: List<Task>) = store.put(TASKS + site, json.encodeToString(taskSerializer, tasks))

    fun dropOfflineTask(site: String, taskId: String) = saveOfflineTasks(site, offlineTasks(site).filterNot { it.id == taskId })

    // ------------------------------------------------------------------ storage

    private fun load(): List<QueuedCommand> =
        store.get(QUEUE)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    @Synchronized
    private fun mutate(change: (List<QueuedCommand>) -> List<QueuedCommand>) {
        val commands = change(state.value)
        state.value = commands
        store.put(QUEUE, json.encodeToString(listSerializer, commands))
    }

    companion object {
        const val QUEUE = "astra.offline.queue"
        const val TASKS = "astra.offline.tasks."

        /** A task command (not the sync-conflict flag itself): group 1 is the task's path. */
        private val TASK_COMMAND = Regex("^(/api/v1/sites/[^/]+/tasks/[0-9a-f-]{36})/(?!sync-conflict)[a-z/-]+$")
    }
}
