package com.astrawms.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrawms.mobile.AppContainer
import com.astrawms.mobile.core.api.ApiException
import com.astrawms.mobile.core.offline.Outcome
import com.astrawms.mobile.core.rfid.TagRead
import com.astrawms.mobile.rfid.ReaderEvent
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no AppContainer") }

private val Navy = Color(0xFF1B3A5C)
private val Amber = Color(0xFFF5A623)

@Composable
fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Navy, secondary = Amber, tertiary = Color(0xFF2E7D32)),
        content = content,
    )
}

/** Busy flag, error and success message of a screen action. */
class ActionState {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun clear() {
        error = null
        message = null
    }
}

@Composable
fun rememberAction() = remember { ActionState() }

/**
 * Runs a screen action: errors from the server show their AstraWMS code (e.g. TSK_WRONG_LPN), a missing network
 * says so. The block returns the success message, or null.
 */
fun CoroutineScope.act(state: ActionState, block: suspend () -> String?) {
    if (state.busy) return
    launch {
        state.busy = true
        state.clear()
        try {
            state.message = block()
        } catch (e: ApiException) {
            state.error = "${e.message} (${e.code})"
        } catch (e: IOException) {
            state.error = "No connection to the server: ${e.message ?: "network unavailable"}"
        } catch (e: Exception) {
            state.error = e.message ?: e.toString()
        } finally {
            state.busy = false
        }
    }
}

/** What to tell the operator after an RF command: done, or kept on the device until the network is back. */
fun <T> Outcome<T>.describe(done: String): String = when (this) {
    is Outcome.Done -> done
    is Outcome.Queued -> "No network: \"$label\" is kept on this device and sent when the network is back"
}

/** Delivers the reader's tags and barcodes to the screen while it is shown. */
@Composable
fun ReaderEvents(onTags: (List<TagRead>) -> Unit = {}, onBarcode: (String) -> Unit = {}) {
    val container = LocalContainer.current
    val tags by rememberUpdatedState(onTags)
    val barcode by rememberUpdatedState(onBarcode)
    LaunchedEffect(container) {
        container.readers.events.collect { e ->
            when (e) {
                is ReaderEvent.Tags -> tags(e.reads)
                is ReaderEvent.Barcode -> barcode(e.text)
                is ReaderEvent.Status -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)?,
    reader: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (reader) ReaderBar()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** The reader's state and, for readers the app can trigger, a Read / Stop button. */
@Composable
fun ReaderBar() {
    val container = LocalContainer.current
    val status by container.readers.status.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().background(if (status.connected) Color(0xFFE8F1E8) else Color(0xFFFFF3E0))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(status.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(status.message, fontSize = 12.sp, color = Color.DarkGray)
        }
        if (container.readers.current.softTrigger) {
            if (status.reading) {
                OutlinedButton(onClick = { container.readers.stopReading() }) { Text("Stop") }
            } else {
                Button(onClick = { container.readers.startReading() }) { Text("Read") }
            }
        }
    }
    if (status.reading) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hint: String? = null,
    numeric: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Ascii,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    )
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, fontSize = 18.sp)
    }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(text) }
}

@Composable
fun Feedback(state: ActionState) {
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Banner(it, Color(0xFFFFEBEE), Color(0xFFB71C1C)) }
    state.message?.let { Banner(it, Color(0xFFE8F5E9), Color(0xFF1B5E20)) }
}

@Composable
fun Banner(text: String, background: Color, color: Color) {
    Text(text, color = color, modifier = Modifier.fillMaxWidth().background(background).padding(10.dp))
}

/** Label / value facts in large type: what the operator must see from arm's length. */
@Composable
fun Facts(vararg facts: Pair<String, String?>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            facts.filter { !it.second.isNullOrBlank() }.forEach { (k, v) ->
                Row {
                    Text(k, color = Color.Gray, modifier = Modifier.width(110.dp))
                    Text(v!!, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
    }
}

/** A row of mutually exclusive choices (reasons, grades, reader types). */
@Composable
fun Choices(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value, label) ->
                    val modifier = Modifier.weight(1f)
                    if (value == selected) {
                        Button(onClick = { onSelect(value) }, modifier = modifier,
                            colors = ButtonDefaults.buttonColors(containerColor = Navy)) { Text(label, fontSize = 13.sp) }
                    } else {
                        OutlinedButton(onClick = { onSelect(value) }, modifier = modifier) { Text(label, fontSize = 13.sp) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

fun qty(v: Double?): String = when {
    v == null -> ""
    v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
    else -> "%.3f".format(v).trimEnd('0').trimEnd('.')
}
