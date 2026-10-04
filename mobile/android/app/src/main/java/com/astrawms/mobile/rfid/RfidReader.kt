package com.astrawms.mobile.rfid

import android.app.Activity
import com.astrawms.mobile.core.rfid.TagRead
import kotlinx.coroutines.flow.SharedFlow

/** What a reader reports to the screens. */
sealed interface ReaderEvent {
    /** Tags of one read (a trigger pull, an inventory round, an NFC tap). */
    data class Tags(val reads: List<TagRead>) : ReaderEvent

    /** A barcode from the same device (DataWedge, a TSL handle with an imager, an NFC text record). */
    data class Barcode(val text: String) : ReaderEvent

    data class Status(val connected: Boolean, val message: String) : ReaderEvent
}

/**
 * A source of RFID tag reads (ADR-0027). Implementations:
 * - [IntentWedgeReader]: Zebra DataWedge (MC3300R, RFD40/RFD90 sleds on Zebra devices) and other scan-to-intent
 *   wedges; the device reads on its own trigger and broadcasts the tags;
 * - [TslBluetoothReader]: Bluetooth UHF readers speaking the TSL ASCII 2.0 protocol (1128, 2128 handles);
 * - [NfcReader]: the phone's NFC for HF tags whose NDEF record carries an EPC, a GS1 Digital Link or a label;
 * - [SimulatedReader]: a configured list of EPCs, for training and the emulator.
 * A vendor SDK reader (Zebra RFID API3, Honeywell, Chainway) plugs in by implementing this interface.
 */
interface RfidReader {
    val name: String

    /** Whether the app can start a read itself (a soft trigger); otherwise the device's trigger starts it. */
    val softTrigger: Boolean

    /** Whether a started read goes on until stopped (an inventory), rather than one round per start. */
    val continuous: Boolean

    /** Whether reads carry RSSI, so "find this tag" can show proximity. */
    val reportsRssi: Boolean

    val events: SharedFlow<ReaderEvent>

    /** Connects (or registers for broadcasts). Safe to call again. */
    fun connect()

    fun disconnect()

    /** Starts reading (continuous until [stopReading], or one round for readers without continuous mode). */
    fun startReading()

    fun stopReading()

    /** Readers that need the foreground activity (NFC reader mode). */
    fun attach(activity: Activity) {}

    fun detach(activity: Activity) {}
}
