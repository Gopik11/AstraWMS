package com.astrawms.mobile.rfid

import android.app.Activity
import android.content.Context
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.astrawms.mobile.core.scan.ScanClassifier
import com.astrawms.mobile.core.scan.ScanInput
import java.util.Arrays
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The phone's NFC (13.56 MHz HF RFID) in reader mode while the app is in front. An NDEF record carrying an EPC (hex
 * or `urn:epc:` URI) is a tag read; a GS1 Digital Link, a GS1 element string or plain text (a bin or LPN label) is a
 * scan, as if it were a barcode. A tag without NDEF data reports its UID, which commissioning can bind (ADR-0027).
 */
class NfcReader(private val context: Context) : RfidReader {

    override val name = "NFC"
    override val softTrigger = false
    override val continuous = false
    override val reportsRssi = false

    private val flow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ReaderEvent> = flow.asSharedFlow()

    private val adapter: NfcAdapter? get() = NfcAdapter.getDefaultAdapter(context)

    override fun connect() {
        val a = adapter
        flow.tryEmit(
            when {
                a == null -> ReaderEvent.Status(false, "This device has no NFC")
                !a.isEnabled -> ReaderEvent.Status(false, "Turn NFC on in the device settings")
                else -> ReaderEvent.Status(true, "Hold the phone to the tag")
            },
        )
    }

    override fun disconnect() {}

    override fun startReading() {}

    override fun stopReading() {}

    override fun attach(activity: Activity) {
        adapter?.enableReaderMode(activity, { tag -> onTag(tag) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
    }

    override fun detach(activity: Activity) {
        adapter?.disableReaderMode(activity)
    }

    private fun onTag(tag: Tag) {
        val texts = ndefTexts(tag)
        if (texts.isEmpty()) {
            // UIDs are 4-10 bytes: padded to an EPC-sized key so the registry can hold it
            val uid = tag.id.joinToString("") { "%02X".format(it) }.padStart(24, '0')
            flow.tryEmit(ReaderEvent.Tags(listOf(com.astrawms.mobile.core.rfid.TagRead(uid))))
            return
        }
        for (text in texts) {
            when (val input = ScanClassifier.classify(text)) {
                is ScanInput.Tags -> flow.tryEmit(ReaderEvent.Tags(input.reads))
                is ScanInput.Barcode -> flow.tryEmit(ReaderEvent.Barcode(input.text))
                null -> Unit
            }
        }
    }

    private fun ndefTexts(tag: Tag): List<String> {
        val ndef = Ndef.get(tag) ?: return emptyList()
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: return emptyList()
            message.records.mapNotNull { record ->
                when {
                    record.tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.type, NdefRecord.RTD_TEXT) -> text(record)
                    else -> record.toUri()?.toString()
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** NFC Forum text record: status byte (UTF-16 flag, language code length), language, text. */
    private fun text(record: NdefRecord): String? {
        val p = record.payload
        if (p.isEmpty()) return null
        val utf16 = p[0].toInt() and 0x80 != 0
        val langLength = p[0].toInt() and 0x3F
        if (1 + langLength > p.size) return null
        return String(p, 1 + langLength, p.size - 1 - langLength, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }
}
