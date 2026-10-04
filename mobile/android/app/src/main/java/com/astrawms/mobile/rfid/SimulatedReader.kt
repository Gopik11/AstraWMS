package com.astrawms.mobile.rfid

import com.astrawms.mobile.core.rfid.TagRead
import kotlin.random.Random
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Training and emulator reader: a read returns the EPCs configured in Settings (one per line), with a random RSSI, as
 * if they were in the field. Lines that are not EPC-shaped are delivered as barcodes.
 */
class SimulatedReader(private val tags: () -> String) : RfidReader {

    override val name = "Simulated reader"
    override val softTrigger = true
    override val continuous = false
    override val reportsRssi = true

    private val flow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ReaderEvent> = flow.asSharedFlow()

    override fun connect() {
        flow.tryEmit(ReaderEvent.Status(true, "Simulated reader: Read returns the EPCs set in Settings"))
    }

    override fun disconnect() {}

    override fun startReading() {
        val lines = tags().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (epcs, other) = lines.partition { com.astrawms.mobile.core.rfid.Epc.looksLikeEpc(it) }
        other.forEach { flow.tryEmit(ReaderEvent.Barcode(it)) }
        if (epcs.isEmpty() && other.isEmpty()) {
            flow.tryEmit(ReaderEvent.Status(true, "No simulated tags: add EPCs in Settings"))
        } else if (epcs.isNotEmpty()) {
            flow.tryEmit(ReaderEvent.Tags(epcs.map { TagRead(it, rssi = -75 + Random.nextInt(40)) }))
        }
    }

    override fun stopReading() {}
}
