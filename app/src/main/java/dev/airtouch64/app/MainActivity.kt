package dev.airtouch64.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.airtouch64.app.ui.theme.AirTouchTheme
import dev.airtouch64.engine.PatchResult
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PatchViewModel by viewModels()
    private var pendingSave: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirTouchTheme {
                ManagerApp(
                    viewModel = viewModel,
                    onInstall = ::install,
                    onSave = { file ->
                        pendingSave = file
                        saveLauncher.launch("ps-touch-revived.apk")
                    },
                )
            }
        }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri == null || file == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error(getString(R.string.save_destination_unavailable))
            Toast.makeText(this, getString(R.string.apk_saved), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, getString(R.string.save_failed, it.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allow_unknown_apps), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.no_installer), Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun ManagerApp(
    viewModel: PatchViewModel,
    onInstall: (File) -> Unit,
    onSave: (File) -> Unit,
) {
    val state = viewModel.state
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Home -> HomeScreen(current, viewModel::importBundle, viewModel::importSource, viewModel::start)
                is UiState.Running -> RunningScreen(current)
                is UiState.Done -> DoneScreen(current.result, onInstall, onSave, viewModel::reset)
                is UiState.Failed -> FailedScreen(current, viewModel::reset)
            }
        }
    }
}

@Composable
private fun ContentColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth > 680.dp) (maxWidth - 640.dp) / 2 else 20.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun HomeScreen(state: UiState.Home, onBundle: (Uri) -> Unit, onPick: (Uri) -> Unit, onStart: () -> Unit) {
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onBundle(uri)
    }
    var showApkHelp by remember { mutableStateOf(false) }
    ContentColumn {
        AppHeader()
        val bundle = state.bundle
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.patch_bundle), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { bundlePicker.launch(arrayOf("*/*")) }, enabled = !state.importingBundle) {
                        Text(stringResource(if (bundle == null) R.string.choose_bundle else R.string.change_bundle))
                    }
                }
                if (bundle != null) {
                    Text(bundle.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.bundle_details, bundle.header.name, bundle.header.version, bundle.file.length() / 1024 / 1024), style = MaterialTheme.typography.bodySmall)
                }
                if (state.importingBundle) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        val source = state.source
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.source_apk), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.supported_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { sourcePicker.launch(arrayOf("*/*")) },
                        enabled = !state.importingSource && bundle != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (source == null) R.string.choose_apk else R.string.change_apk), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showApkHelp = true },
                        enabled = !state.importingSource,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.no_apk), maxLines = 1)
                    }
                }
                if (state.importingSource) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.reading_apk), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (source != null) {
                    HorizontalDivider()
                    Text(source.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.source_details, source.inspection.sha256.take(16), source.inspection.size / 1024 / 1024, source.inspection.entryCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val supported = source.inspection.supported
                    Text(
                        stringResource(if (supported) R.string.apk_compatible else R.string.apk_incompatible),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (supported) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                    if (!supported && source.inspection.missingEntries.isNotEmpty()) {
                        Text(
                            stringResource(R.string.missing_files, source.inspection.missingEntries.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        if (bundle != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.included_patches), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.version, "1.0.0"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                bundle.header.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stepTitle(step.id, step.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(stepDescription(step.id, step.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Text(
            stringResource(R.string.install_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onStart,
            enabled = bundle != null && source?.inspection?.supported == true && !state.importingBundle && !state.importingSource,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.apply_patches)) }
    }
    if (showApkHelp) ApkHelpDialog(onDismiss = { showApkHelp = false })
}

@Composable
private fun AppHeader() {
    Text(
        "AIR TOUCH 64",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun stepTitle(id: String, fallback: String): String {
    val resource = when (id) {
        "validate" -> R.string.step_validate
        "swf" -> R.string.step_swf
        "dex" -> R.string.step_dex
        "resources" -> R.string.step_resources
        "assemble" -> R.string.step_assemble
        "sign" -> R.string.step_sign
        else -> return fallback
    }
    return stringResource(resource)
}

@Composable
private fun stepDescription(id: String, fallback: String): String {
    val resource = when (id) {
        "validate" -> R.string.step_validate_description
        "swf" -> R.string.step_swf_description
        "dex" -> R.string.step_dex_description
        "resources" -> R.string.step_resources_description
        "assemble" -> R.string.step_assemble_description
        "sign" -> R.string.step_sign_description
        else -> return fallback
    }
    return stringResource(resource)
}

@Composable
private fun ApkHelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.apk_download_options))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.apkmirror.com/apk/adobe/adobe-photoshop-touch-phone/ps-touch-1-3-7-release/")))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("APKMirror", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://apkpure.net/br/adobe-photoshop-touch/air.com.adobe.pstouchphone/download")))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Apkpure", maxLines = 1) }
                }
                Text(
                    stringResource(R.string.third_party_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun RunningScreen(state: UiState.Running) {
    ContentColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Column {
                Text(stringResource(R.string.applying_patches), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.do_not_close), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val total = state.header.steps.size
        val completeCount = state.completed.size
        LinearProgressIndicator(
            progress = {
                if (total == 0) 0f else ((completeCount + if (state.currentStep != null) 0.5f else 0f) / total).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.header.steps.forEach { step ->
                    val done = step.id in state.completed
                    val running = step.id == state.currentStep
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when { done -> stringResource(R.string.step_done, stepTitle(step.id, step.name)); running -> stringResource(R.string.step_running, stepTitle(step.id, step.name)); else -> stepTitle(step.id, step.name) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = when { done -> MaterialTheme.colorScheme.tertiary; running -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                            fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(stepDescription(step.id, step.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        LogCard(state.log)
    }
}

@Composable
private fun LogCard(log: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.log), style = MaterialTheme.typography.titleSmall)
            if (log.isEmpty()) Text(stringResource(R.string.waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.takeLast(40).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DoneScreen(result: PatchResult, onInstall: (File) -> Unit, onSave: (File) -> Unit, onReset: () -> Unit) {
    ContentColumn {
        Text(stringResource(R.string.apk_ready), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
        Text(stringResource(R.string.patches_applied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue(stringResource(R.string.file), result.output.name)
                LabeledValue(stringResource(R.string.size), "${result.size / 1024 / 1024} MiB")
                LabeledValue(stringResource(R.string.entries), result.entries.toString())
                LabeledValue("SHA-256", result.sha256.chunked(32).joinToString("\n"))
                LabeledValue(stringResource(R.string.signature), result.signerFingerprint.chunked(32).joinToString("\n"))
            }
        }
        Button(onClick = { onInstall(result.output) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.install)) }
        OutlinedButton(onClick = { onSave(result.output) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.save_apk)) }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun FailedScreen(state: UiState.Failed, onReset: () -> Unit) {
    ContentColumn {
        Text(stringResource(R.string.could_not_complete), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(state.message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (state.log.isNotEmpty()) LogCard(state.log)
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = true)
    }
}
