package com.astrawms.mobile.rfid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.astrawms.mobile.core.rfid.TagRead
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bluetooth UHF readers that speak the TSL ASCII 2.0 protocol over the serial port profile (TSL 1128 / 2128
 * handles and compatible sleds). The reader runs an inventory on its own trigger and reports each tag as an `EP:` line
 * (with `RI:` for RSSI when asked); `BC:` lines are barcodes from readers with an imager. The app's Read button sends
 * `.iv` rounds while held.
 */
class TslBluetoothReader(private val context: Context, private val address: String) : RfidReader {

    override val name = "Bluetooth reader $address"
    override val softTrigger = true
    override val continuous = true
    override val reportsRssi = true

    private val flow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ReaderEvent> = flow.asSharedFlow()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reading: Job? = null
    @Volatile private var connecting = false

    @SuppressLint("MissingPermission")   // checked in hasPermission()
    override fun connect() {
        if (socket?.isConnected == true || connecting) return
        if (!hasPermission()) {
            flow.tryEmit(ReaderEvent.Status(false, "Allow Bluetooth (nearby devices) for the app in Settings"))
            return
        }
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connecting = true
        scope.launch {
            try {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IOException("This device has no Bluetooth")
                val device = adapter.getRemoteDevice(address)
                val s = device.createRfcommSocketToServiceRecord(SPP)
                s.connect()
                socket = s
                out = s.outputStream
                // tags with RSSI, EPC only, no beep from the app's own rounds
                send(".iv -r on -n -x")
                connecting = false
                flow.tryEmit(ReaderEvent.Status(true, "Connected to $address"))
                readLines(s)
            } catch (e: IOException) {
                flow.tryEmit(ReaderEvent.Status(false, "Bluetooth reader: ${e.message ?: "not connected"}"))
                close()
            } catch (e: IllegalArgumentException) {
                flow.tryEmit(ReaderEvent.Status(false, "Bluetooth address $address is not valid"))
            } finally {
                connecting = false
            }
        }
    }

    /** Parses the reader's response lines; one inventory round's tags are reported together at its `OK:`. */
    private fun readLines(s: BluetoothSocket) {
        val pending = ArrayList<TagRead>()
        var epc: String? = null
        var rssi: Int? = null
        fun flushTag() {
            epc?.let { pending += TagRead(it, rssi) }
            epc = null
            rssi = null
        }
        s.inputStream.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.startsWith("EP:") -> { flushTag(); epc = line.substring(3).trim() }
                    line.startsWith("RI:") -> rssi = line.substring(3).trim().toIntOrNull()
                    line.startsWith("BC:") -> flow.tryEmit(ReaderEvent.Barcode(line.substring(3).trim()))
                    line.startsWith("OK:") || line.startsWith("ER:") -> {
                        flushTag()
                        if (pending.isNotEmpty()) {
                            flow.tryEmit(ReaderEvent.Tags(pending.toList()))
                            pending.clear()
                        }
                        if (line.startsWith("ER:")) flow.tryEmit(ReaderEvent.Status(true, "Reader: $line"))
                    }
                }
            }
        }
        throw IOException("Connection closed")
    }

    override fun startReading() {
        if (reading?.isActive == true) return
        reading = scope.launch {
            while (isActive && out != null) {
                send(".iv")
                delay(350)
            }
        }
    }

    override fun stopReading() {
        reading?.cancel()
        reading = null
    }

    override fun disconnect() {
        stopReading()
        close()
        scope.cancel()
    }

    private fun send(command: String) {
        try {
            out?.apply { write("$command\r\n".toByteArray(Charsets.US_ASCII)); flush() }
        } catch (e: IOException) {
            flow.tryEmit(ReaderEvent.Status(false, "Bluetooth reader disconnected"))
            close()
        }
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
