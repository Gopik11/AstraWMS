package com.astrawms.mobile

import android.content.Context
import android.content.SharedPreferences
import com.astrawms.mobile.core.offline.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the device reads tags and scans (ADR-0027). */
enum class ReaderType(val label: String) {
    DATAWEDGE("Zebra DataWedge (RFID + barcode)"),
    INTENT("Other scan-to-intent wedge"),
    TSL_BLUETOOTH("Bluetooth UHF reader (TSL ASCII)"),
    NFC("Phone NFC (HF tags)"),
    SIMULATED("Simulated reader (training)"),
}

/** Device settings: where the gateway is, which site, and how RFID / barcodes come in. */
data class DeviceSettings(
    /** The AstraWMS gateway, e.g. https://astrawms.cloud or http://10.0.2.2:8080 from the emulator. */
    val gatewayUrl: String = "",
    /** The identity provider issuer; blank = the gateway's /config.json authority (the web UI's own setting). */
    val issuerOverride: String = "",
    val clientId: String = "astra-mobile",
    val site: String = "",
    val readerType: ReaderType = ReaderType.DATAWEDGE,
    /** Intent wedges: the broadcast action and extras the wedge sends. */
    val intentAction: String = DEFAULT_INTENT_ACTION,
    val dataExtra: String = "com.symbol.datawedge.data_string",
    val labelTypeExtra: String = "com.symbol.datawedge.label_type",
    val sourceExtra: String = "com.symbol.datawedge.source",
    /** Bluetooth readers: the paired device's MAC address. */
    val bluetoothAddress: String = "",
    /** GS1 company prefix length for tags the WMS encodes (commissioning). */
    val companyPrefixLength: Int = 7,
    /** Simulated reader: the EPCs a "read" returns, one per line. */
    val simulatedTags: String = "",
) {
    companion object {
        const val DEFAULT_INTENT_ACTION = "com.astrawms.mobile.SCAN"
    }
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("astra_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    val current: DeviceSettings get() = state.value

    fun update(change: (DeviceSettings) -> DeviceSettings) {
        val s = change(state.value)
        prefs.edit()
            .putString("gatewayUrl", s.gatewayUrl.trim().trimEnd('/'))
            .putString("issuerOverride", s.issuerOverride.trim().trimEnd('/'))
            .putString("clientId", s.clientId.trim())
            .putString("site", s.site.trim().uppercase())
            .putString("readerType", s.readerType.name)
            .putString("intentAction", s.intentAction.trim())
            .putString("dataExtra", s.dataExtra.trim())
            .putString("labelTypeExtra", s.labelTypeExtra.trim())
            .putString("sourceExtra", s.sourceExtra.trim())
            .putString("bluetoothAddress", s.bluetoothAddress.trim().uppercase())
            .putInt("companyPrefixLength", s.companyPrefixLength)
            .putString("simulatedTags", s.simulatedTags)
            .apply()
        state.value = read()
    }

    private fun read(): DeviceSettings {
        val d = DeviceSettings()
        return DeviceSettings(
            gatewayUrl = prefs.getString("gatewayUrl", d.gatewayUrl) ?: d.gatewayUrl,
            issuerOverride = prefs.getString("issuerOverride", d.issuerOverride) ?: d.issuerOverride,
            clientId = prefs.getString("clientId", d.clientId)?.ifBlank { d.clientId } ?: d.clientId,
            site = prefs.getString("site", d.site) ?: d.site,
            readerType = prefs.getString("readerType", null)?.let { n -> ReaderType.entries.firstOrNull { it.name == n } }
                ?: d.readerType,
            intentAction = prefs.getString("intentAction", d.intentAction)?.ifBlank { d.intentAction } ?: d.intentAction,
            dataExtra = prefs.getString("dataExtra", d.dataExtra)?.ifBlank { d.dataExtra } ?: d.dataExtra,
            labelTypeExtra = prefs.getString("labelTypeExtra", d.labelTypeExtra) ?: d.labelTypeExtra,
            sourceExtra = prefs.getString("sourceExtra", d.sourceExtra) ?: d.sourceExtra,
            bluetoothAddress = prefs.getString("bluetoothAddress", d.bluetoothAddress) ?: d.bluetoothAddress,
            companyPrefixLength = prefs.getInt("companyPrefixLength", d.companyPrefixLength),
            simulatedTags = prefs.getString("simulatedTags", d.simulatedTags) ?: d.simulatedTags,
        )
    }
}

/** The offline queue and downloaded tasks live in plain app-private preferences (no secrets in them). */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("astra_offline", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
