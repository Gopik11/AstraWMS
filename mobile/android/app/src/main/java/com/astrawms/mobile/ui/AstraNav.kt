package com.astrawms.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.astrawms.mobile.ReaderType
import com.astrawms.mobile.core.offline.QueuedCommand
import java.text.DateFormat
import java.util.Date

@Composable
fun AstraNav() {
    val container = LocalContainer.current
    val session by container.auth.session.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    // decided once: later sign-in / sign-out navigate explicitly
    val start = remember { if (session == null) "login" else "home" }
    NavHost(nav, startDestination = start) {
        composable("login") { LoginScreen(onSettings = { nav.navigate("settings") }, onSignedIn = {
            nav.navigate("home") { popUpTo("login") { inclusive = true } }
        }) }
        composable("home") {
            HomeScreen(open = { nav.navigate(it) }, onSignedOut = {
                nav.navigate("login") { popUpTo("home") { inclusive = true } }
            })
        }
        composable("work") { WorkScreen(back) }
        composable("check") { LocationCheckScreen(back) }
        composable("lookup") { TagLookupScreen(back) }
        composable("commission") { CommissionScreen(back) }
        composable("queue") { QueueScreen(back) }
        composable("settings") { SettingsScreen(back) }
    }
}

@Composable
fun LoginScreen(onSettings: () -> Unit, onSignedIn: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var gateway by remember { mutableStateOf(settings.gatewayUrl) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.act(state) {
            container.auth.completeSignIn(result.data)
            onSignedIn()
            null
        }
    }
    Screen("AstraWMS RF", onBack = null, reader = false, actions = { TextButton(onClick = onSettings) { Text("Settings", color = Color.White) } }) {
        Text("Sign in with your AstraWMS account. Passkeys (face, fingerprint) work on the sign-in page.")
        Field("Gateway URL", gateway, { gateway = it }, hint = "e.g. https://astrawms.cloud, or http://10.0.2.2:8080 on the emulator")
        Feedback(state)
        PrimaryButton("Sign in", enabled = gateway.isNotBlank() && !state.busy) {
            container.settings.update { it.copy(gatewayUrl = gateway) }
            scope.act(state) {
                launcher.launch(container.auth.signInIntent())
                null
            }
        }
    }
}

@Composable
fun HomeScreen(open: (String) -> Unit, onSignedOut: () -> Unit) {
    val container = LocalContainer.current
    val session by container.auth.session.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val commands by container.queue.commands.collectAsStateWithLifecycle()
    val signOut = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onSignedOut() }
    val s = session ?: return
    var siteText by remember(settings.site) { mutableStateOf(settings.site) }
    LaunchedEffect(s.sites) {
        // a user scoped to one site works there; otherwise the device keeps its last site
        val only = s.sites?.singleOrNull()
        if (settings.site.isBlank() && only != null) container.settings.update { it.copy(site = only) }
    }
    Screen("AstraWMS RF", onBack = null, actions = { TextButton(onClick = { open("settings") }) { Text("Settings", color = Color.White) } }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(s.userName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Tenant ${s.tenant} · ${s.roles.sorted().joinToString(", ").ifEmpty { "no AstraWMS roles" }}", color = Color.Gray)
            }
        }
        if (s.sites == null || s.sites.size != 1) {
            if (s.sites.isNullOrEmpty()) {
                Field("Site", siteText, { siteText = it.uppercase() }, onDone = { container.settings.update { it.copy(site = siteText) } })
                SecondaryButton("Use site $siteText", enabled = siteText.isNotBlank()) { container.settings.update { it.copy(site = siteText) } }
            } else {
                Text("Site")
                Choices(s.sites.map { it to it }, settings.site) { site -> container.settings.update { it.copy(site = site) } }
            }
        } else {
            Facts("Site" to settings.site)
        }
        val ready = settings.site.isNotBlank()
        if (!ready) Banner("Choose the site you work at", Color(0xFFFFF3E0), Color(0xFF8D4F00))
        val pending = commands.count { it.status == QueuedCommand.PENDING }
        val failed = commands.count { it.status == QueuedCommand.FAILED }
        if (pending + failed > 0) {
            Banner("$pending scan(s) waiting to be sent, $failed need attention", Color(0xFFFFF3E0), Color(0xFF8D4F00))
        }
        SectionTitle("Work")
        PrimaryButton("RF work (next task)", enabled = ready) { open("work") }
        SectionTitle("RFID")
        SecondaryButton("Check a location (RFID count)", enabled = ready) { open("check") }
        SecondaryButton("Identify / find tags", enabled = ready) { open("lookup") }
        if (s.hasRole("RECEIVER", "INV_ANALYST", "INV_MANAGER", "SUPERVISOR")) {
            SecondaryButton("Commission a tag", enabled = ready) { open("commission") }
        }
        SectionTitle("Device")
        SecondaryButton("Offline queue (${commands.size})") { open("queue") }
        SecondaryButton("Sign out") {
            val intent = container.auth.signOutIntent()
            if (intent != null) signOut.launch(intent) else onSignedOut()
        }
    }
}

@Composable
fun QueueScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state = rememberAction()
    val commands by container.queue.commands.collectAsStateWithLifecycle()
    Screen("Offline queue", onBack, reader = false) {
        Text("RF scans done without network are sent in order when it is back, with the same idempotency key (a scan that already reached the server is not done twice). A scan the server refuses needs attention: check the location, then retry or discard it.")
        Feedback(state)
        PrimaryButton("Send now", enabled = commands.any { it.status == QueuedCommand.PENDING }) {
            scope.act(state) {
                val r = container.queue.sync()
                "${r.sent} sent, ${r.failed} refused"
            }
        }
        if (commands.isEmpty()) Text("Nothing waiting.", color = Color.Gray)
        commands.forEach { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.label, fontWeight = FontWeight.Bold)
                    Text("${c.status} · ${DateFormat.getTimeInstance().format(Date(c.createdAt))}", color = Color.Gray)
                    c.error?.let { Text(it, color = Color(0xFFB71C1C)) }
                    if (c.status == QueuedCommand.FAILED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { container.queue.retry(c.id) }) { Text("Retry") }
                            TextButton(onClick = { container.queue.discard(c.id) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val current by container.settings.settings.collectAsStateWithLifecycle()
    var s by remember { mutableStateOf(current) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Screen("Settings", onBack, reader = false) {
        SectionTitle("Server")
        Field("Gateway URL", s.gatewayUrl, { s = s.copy(gatewayUrl = it) })
        Field("Identity provider (issuer)", s.issuerOverride, { s = s.copy(issuerOverride = it) },
            hint = "Blank: the gateway's /config.json authority")
        Field("Client ID", s.clientId, { s = s.copy(clientId = it) })
        SectionTitle("RFID reader")
        Choices(ReaderType.entries.map { it.name to it.label }, s.readerType.name) { n ->
            s = s.copy(readerType = ReaderType.valueOf(n))
            if (s.readerType == ReaderType.TSL_BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        when (s.readerType) {
            ReaderType.DATAWEDGE, ReaderType.INTENT -> {
                Field("Intent action", s.intentAction, { s = s.copy(intentAction = it) },
                    hint = if (s.readerType == ReaderType.DATAWEDGE) "The app creates the DataWedge profile \"AstraWMS\" with this action" else "The broadcast action your wedge sends")
                Field("Data extra", s.dataExtra, { s = s.copy(dataExtra = it) })
                Field("Label type extra", s.labelTypeExtra, { s = s.copy(labelTypeExtra = it) })
                Field("Source extra", s.sourceExtra, { s = s.copy(sourceExtra = it) })
            }
            ReaderType.TSL_BLUETOOTH -> Field("Reader Bluetooth address", s.bluetoothAddress, { s = s.copy(bluetoothAddress = it) },
                hint = "Pair the reader in Android settings first, e.g. 00:11:22:33:44:55")
            ReaderType.SIMULATED -> OutlinedTextField(
                value = s.simulatedTags, onValueChange = { s = s.copy(simulatedTags = it) },
                label = { Text("Simulated tags (one EPC per line)") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            ReaderType.NFC -> Text("Hold the phone to NFC tags; NDEF records with an EPC, GS1 Digital Link or label text are read.")
        }
        SectionTitle("Tag encoding")
        Field("GS1 company prefix length", s.companyPrefixLength.toString(),
            { v -> v.toIntOrNull()?.let { s = s.copy(companyPrefixLength = it.coerceIn(6, 12)) } }, numeric = true,
            hint = "6-12 digits; used when the WMS encodes SGTIN-96 / SSCC-96 tags")
        PrimaryButton("Save") {
            container.settings.update { s }
            onBack()
        }
    }
}
