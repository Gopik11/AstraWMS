package com.astrawms.mobile.rfid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.astrawms.mobile.DeviceSettings
import com.astrawms.mobile.core.scan.ScanClassifier
import com.astrawms.mobile.core.scan.ScanInput
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Scan-to-intent wedges: the device's own scanning service reads on the hardware trigger and broadcasts what it read.
 * Zebra DataWedge does so for barcodes and, with its RFID input plugin, for UHF tags (MC3300R / MC3390R, RFD40 / RFD90
 * sleds), several tags of one trigger pull per broadcast, one per line. Other vendors' wedges work when their action and
 * extras are set in Settings. With DataWedge the app creates its own profile (intent output, RFID + barcode input) and
 * can start a read itself (soft RFID trigger).
 */
class IntentWedgeReader(
    private val context: Context,
    private val settings: DeviceSettings,
    private val dataWedge: Boolean,
) : RfidReader {

    override val name = if (dataWedge) "DataWedge" else "Scan intent"
    override val softTrigger = dataWedge
    override val continuous = true
    override val reportsRssi = false

    private val flow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ReaderEvent> = flow.asSharedFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val data = intent.getStringExtra(settings.dataExtra) ?: return
            val labelType = settings.labelTypeExtra.takeIf { it.isNotBlank() }?.let { intent.getStringExtra(it) }
            val source = settings.sourceExtra.takeIf { it.isNotBlank() }?.let { intent.getStringExtra(it) }
            val rfid = source?.contains("rfid", ignoreCase = true) == true
            when (val input = ScanClassifier.classify(data, if (rfid) "RFID" else labelType)) {
                is ScanInput.Tags -> flow.tryEmit(ReaderEvent.Tags(input.reads))
                is ScanInput.Barcode -> flow.tryEmit(ReaderEvent.Barcode(input.text))
                null -> Unit
            }
        }
    }

    override fun connect() {
        if (registered) return
        if (dataWedge) configureDataWedge()
        ContextCompat.registerReceiver(context, receiver, IntentFilter(settings.intentAction), ContextCompat.RECEIVER_EXPORTED)
        registered = true
        flow.tryEmit(ReaderEvent.Status(true, "$name ready: pull the trigger to read"))
    }

    override fun disconnect() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    override fun startReading() = softRfidTrigger("START_SCANNING")

    override fun stopReading() = softRfidTrigger("STOP_SCANNING")

    private fun softRfidTrigger(command: String) {
        if (!dataWedge) return
        context.sendBroadcast(Intent(DW_ACTION).setPackage(DW_PACKAGE).putExtra("$DW_API.SOFT_RFID_TRIGGER", command))
    }

    /**
     * Creates the DataWedge profile "AstraWMS" for this app when it does not exist: intent output (broadcast) to
     * [DeviceSettings.intentAction], keystroke output off, barcode and RFID input on with the hardware trigger.
     */
    private fun configureDataWedge() {
        fun plugin(name: String, params: Bundle) = Bundle().apply {
            putString("PLUGIN_NAME", name)
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", params)
        }
        val profile = Bundle().apply {
            putString("PROFILE_NAME", "AstraWMS")
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf(Bundle().apply {
                putString("PACKAGE_NAME", context.packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }))
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(
                plugin("INTENT", Bundle().apply {
                    putString("intent_output_enabled", "true")
                    putString("intent_action", settings.intentAction)
                    putString("intent_delivery", "2")                      // broadcast
                }),
                plugin("KEYSTROKE", Bundle().apply { putString("keystroke_output_enabled", "false") }),
                plugin("BARCODE", Bundle().apply {
                    putString("scanner_selection", "auto")
                    putString("scanner_input_enabled", "true")
                }),
                plugin("RFID", Bundle().apply {
                    putString("rfid_input_enabled", "true")
                    putString("rfid_hardware_trigger_enabled", "true")
                }),
            ))
        }
        context.sendBroadcast(Intent(DW_ACTION).setPackage(DW_PACKAGE).putExtra("$DW_API.SET_CONFIG", profile))
    }

    private companion object {
        const val DW_PACKAGE = "com.symbol.datawedge"
        const val DW_API = "com.symbol.datawedge.api"
        const val DW_ACTION = "$DW_API.ACTION"
    }
}
