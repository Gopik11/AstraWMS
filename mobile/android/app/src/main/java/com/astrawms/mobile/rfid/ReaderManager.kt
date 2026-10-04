package com.astrawms.mobile.rfid

import android.app.Activity
import android.content.Context
import com.astrawms.mobile.DeviceSettings
import com.astrawms.mobile.ReaderType
import com.astrawms.mobile.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReaderStatus(val name: String, val connected: Boolean, val message: String, val reading: Boolean)

/**
 * The reader chosen in Settings, re-created when the settings change. Screens collect [events] while visible; the
 * status line shows [status].
 */
class ReaderManager(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {

    private var reader: RfidReader = create(settings.current)
    private var built: DeviceSettings = settings.current
    private var forwarding: Job? = null
    private var activity: Activity? = null

    private val flow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ReaderEvent> = flow.asSharedFlow()

    private val statusState = MutableStateFlow(ReaderStatus(reader.name, false, "Not connected", false))
    val status: StateFlow<ReaderStatus> = statusState.asStateFlow()

    val current: RfidReader get() = reader

    init {
        scope.launch {
            settings.settings.collect { s ->
                if (readerSettingsChanged(built, s)) rebuild(s)
            }
        }
    }

    fun start(activity: Activity) {
        this.activity = activity
        forward()
        reader.connect()
        reader.attach(activity)
    }

    fun stop(activity: Activity) {
        reader.stopReading()
        reader.detach(activity)
        if (this.activity === activity) this.activity = null
    }

    fun startReading() {
        reader.startReading()
        statusState.value = statusState.value.copy(reading = reader.continuous)
    }

    fun stopReading() {
        reader.stopReading()
        statusState.value = statusState.value.copy(reading = false)
    }

    private fun rebuild(s: DeviceSettings) {
        activity?.let { reader.detach(it) }
        reader.disconnect()
        reader = create(s)
        built = s
        statusState.value = ReaderStatus(reader.name, false, "Not connected", false)
        forward()
        activity?.let { a ->
            reader.connect()
            reader.attach(a)
        }
    }

    private fun forward() {
        forwarding?.cancel()
        val r = reader
        forwarding = scope.launch {
            r.events.collect { e ->
                if (e is ReaderEvent.Status) statusState.value = statusState.value.copy(connected = e.connected, message = e.message)
                flow.emit(e)
            }
        }
    }

    private fun create(s: DeviceSettings): RfidReader = when (s.readerType) {
        ReaderType.DATAWEDGE -> IntentWedgeReader(context, s, dataWedge = true)
        ReaderType.INTENT -> IntentWedgeReader(context, s, dataWedge = false)
        ReaderType.TSL_BLUETOOTH -> TslBluetoothReader(context, s.bluetoothAddress)
        ReaderType.NFC -> NfcReader(context)
        ReaderType.SIMULATED -> SimulatedReader { settings.current.simulatedTags }
    }

    private fun readerSettingsChanged(a: DeviceSettings, b: DeviceSettings) =
        a.readerType != b.readerType || a.intentAction != b.intentAction || a.dataExtra != b.dataExtra ||
            a.labelTypeExtra != b.labelTypeExtra || a.sourceExtra != b.sourceExtra || a.bluetoothAddress != b.bluetoothAddress
}
