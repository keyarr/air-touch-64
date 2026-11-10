package dev.airtouch64.app

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.airtouch64.app.ui.theme.AdobeCyan
import dev.airtouch64.app.ui.theme.AdobeCyanMuted
import dev.airtouch64.app.ui.theme.AirTouchTheme
import dev.airtouch64.app.ui.theme.DarkroomBorder
import dev.airtouch64.app.ui.theme.DarkroomCard
import dev.airtouch64.app.ui.theme.DarkroomPanel
import dev.airtouch64.app.ui.theme.DarkroomVoid
import dev.airtouch64.app.ui.theme.StatusError
import dev.airtouch64.app.ui.theme.StatusErrorBg
import dev.airtouch64.app.ui.theme.StatusSuccess
import dev.airtouch64.app.ui.theme.StatusSuccessBg
import dev.airtouch64.app.ui.theme.TextMuted
import dev.airtouch64.app.ui.theme.TextMonospace
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
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.surface) {
            when (val current = state) {
                UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AdobeCyan)
                }
                is UiState.Home -> HomeScreen(current, viewModel::importSource, viewModel::start)
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
        val horizontalPadding = if (maxWidth >= 600.dp) (maxWidth - 640.dp).coerceAtLeast(0.dp) / 2 else 16.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, DarkroomBorder, shape),
        shape = shape,
        color = DarkroomPanel,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun HomeScreen(state: UiState.Home, onPick: (Uri) -> Unit, onStart: () -> Unit) {
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    var showApkHelp by remember { mutableStateOf(false) }
    ContentColumn {
        AppHeader()
        val bundle = state.bundle
        val source = state.source
        Panel {
            Text(stringResource(R.string.source_apk).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.supported_version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { sourcePicker.launch(arrayOf("*/*")) },
                    enabled = !state.importingSource,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(4.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = !state.importingSource),
                ) {
                    Text(stringResource(if (source == null) R.string.choose_apk else R.string.change_apk), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showApkHelp = true },
                    enabled = !state.importingSource,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(stringResource(R.string.no_apk), maxLines = 1)
                }
            }
            if (state.importingSource) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AdobeCyan)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.reading_apk), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (source != null) {
                val supported = source.inspection.supported
                HorizontalDivider(color = if (supported) StatusSuccess else StatusError, thickness = 2.dp)
                StatusBadge(
                    text = stringResource(if (supported) R.string.apk_compatible else R.string.apk_incompatible).uppercase(),
                    color = if (supported) StatusSuccess else StatusError,
                    background = if (supported) StatusSuccessBg else StatusErrorBg,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetadataValue("ARQUIVO", source.name)
                        MetadataValue("ENTRADAS", "${source.inspection.entryCount} arquivos")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetadataValue("TAMANHO", "${source.inspection.size / 1024 / 1024} MiB")
                        HashValue("SHA-256", source.inspection.sha256)
                    }
                }
                if (!supported && source.inspection.missingEntries.isNotEmpty()) {
                    Surface(color = StatusErrorBg, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            stringResource(R.string.missing_files, source.inspection.missingEntries.joinToString(", ")),
                            Modifier.fillMaxWidth().padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                        )
                    }
                }
            }
            state.error?.let { Text(it, color = StatusError, style = MaterialTheme.typography.bodyMedium) }
        }

        if (bundle != null) Panel {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.included_patches).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.version, "1.0.0"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            bundle.header.steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text("[${(index + 1).toString().padStart(2, '0')}]", style = MaterialTheme.typography.labelMedium, color = TextMuted, fontFamily = FontFamily.Monospace)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stepTitle(step.id, step.name).uppercase(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(stepDescription(step.id, step.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.install_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryAction(
            onClick = onStart,
            enabled = source?.inspection?.supported == true && !state.importingSource,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("INICIAR PATCH ARM64", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
    }
    if (showApkHelp) ApkHelpDialog(onDismiss = { showApkHelp = false })
}

@Composable
private fun AppHeader() {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("AIR TOUCH 64", fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("ARM64 INJECTOR · V1.3.7 REVIVAL", Modifier.offset(y = 2.dp), fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.4.sp)
            }
            Surface(
                modifier = Modifier.size(30.dp).border(1.dp, AdobeCyan, RoundedCornerShape(2.dp)),
                color = AdobeCyanMuted,
                shape = RoundedCornerShape(2.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("PS", color = AdobeCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = DarkroomBorder)
    }
}

@Composable
private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Surface(color = background, shape = RoundedCornerShape(3.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetadataValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextMonospace, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HashValue(label: String, value: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.take(12) + "…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextMonospace, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { copyToClipboard(context, label, value) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("COPIAR", fontSize = 9.sp, color = AdobeCyan)
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AdobeCyan,
            contentColor = DarkroomVoid,
            disabledContainerColor = DarkroomCard,
            disabledContentColor = TextMuted,
        ),
    ) { content() }
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
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("APKMirror", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://apkpure.net/br/adobe-photoshop-touch/air.com.adobe.pstouchphone/download")))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(4.dp),
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
        containerColor = DarkroomPanel,
        shape = RoundedCornerShape(6.dp),
    )
}

@Composable
private fun RunningScreen(state: UiState.Running) {
    val total = state.header.steps.size
    val completeCount = state.completed.size
    val progress = if (total == 0) 0f else ((completeCount + if (state.currentStep != null) 0.5f else 0f) / total).coerceIn(0f, 1f)
    ContentColumn {
        AppHeader()
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.applying_patches).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${(progress * 100).toInt()}%", fontFamily = FontFamily.Monospace, color = AdobeCyan, fontSize = 12.sp)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = AdobeCyan, trackColor = DarkroomCard)
            Text(
                state.currentStep?.let { id -> state.header.steps.firstOrNull { it.id == id }?.let { stepTitle(it.id, it.name).uppercase() } } ?: stringResource(R.string.do_not_close).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Panel {
            state.header.steps.forEachIndexed { index, step ->
                val done = step.id in state.completed
                val running = step.id == state.currentStep
                val marker = when { done -> "[✓]"; running -> "[▶]"; else -> "[ ]" }
                val color = when { done -> StatusSuccess; running -> AdobeCyan; else -> TextMuted }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(marker, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stepTitle(step.id, step.name).uppercase(), style = MaterialTheme.typography.bodyMedium, color = if (running) AdobeCyan else MaterialTheme.colorScheme.onSurface, fontWeight = if (running) FontWeight.SemiBold else FontWeight.Medium)
                        Text(stepDescription(step.id, step.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${(index + 1).toString().padStart(2, '0')}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        LogCard(state.log, maxLines = 60, active = true)
    }
}

@Composable
private fun LogCard(log: List<String>, maxLines: Int = 60, active: Boolean = false) {
    val scrollState = rememberScrollState()
    LaunchedEffect(log.size) { scrollState.animateScrollTo(scrollState.maxValue) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, DarkroomBorder, RoundedCornerShape(4.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.log).uppercase(), Modifier.padding(start = 12.dp, top = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().height(240.dp).verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (log.isEmpty()) Text(stringResource(R.string.waiting), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            log.takeLast(maxLines).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextMonospace)
            }
        }
        if (active) HorizontalDivider(color = AdobeCyan.copy(alpha = 0.45f), thickness = 1.dp)
    }
}

@Composable
private fun DoneScreen(result: PatchResult, onInstall: (File) -> Unit, onSave: (File) -> Unit, onReset: () -> Unit) {
    ContentColumn {
        AppHeader()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✓", color = StatusSuccess, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.apk_ready).uppercase(), style = MaterialTheme.typography.titleLarge, color = StatusSuccess, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.patches_applied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Panel {
            LabeledValue("ARQUIVO DE SAÍDA", result.output.name)
            HorizontalDivider(color = DarkroomBorder)
            LabeledValue("TAMANHO FINAL", "${result.size / 1024 / 1024} MiB")
            HorizontalDivider(color = DarkroomBorder)
            LabeledValue("ENTRADAS TOTAIS", "${result.entries} entradas")
            HorizontalDivider(color = DarkroomBorder)
            LabeledValue("SHA-256 DO PACOTE", result.sha256.chunked(32).joinToString("\n"))
            HorizontalDivider(color = DarkroomBorder)
            LabeledValue("FINGERPRINT DA CHAVE", result.signerFingerprint.chunked(32).joinToString("\n"))
        }
        PrimaryAction(onClick = { onInstall(result.output) }, enabled = true, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.install).uppercase(), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { onSave(result.output) }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(4.dp)) {
            Text(stringResource(R.string.save_apk).uppercase())
        }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("PROCESSAR OUTRO APK", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun FailedScreen(state: UiState.Failed, onReset: () -> Unit) {
    val context = LocalContext.current
    ContentColumn {
        AppHeader()
        Panel {
            HorizontalDivider(color = StatusError, thickness = 2.dp)
            Text(stringResource(R.string.could_not_complete).uppercase(), style = MaterialTheme.typography.titleLarge, color = StatusError, fontWeight = FontWeight.Bold)
            Surface(color = StatusErrorBg, shape = RoundedCornerShape(4.dp)) {
                Text(state.message, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = StatusError)
            }
        }
        if (state.log.isNotEmpty()) {
            LogCard(state.log, maxLines = Int.MAX_VALUE)
            OutlinedButton(
                onClick = { copyToClipboard(context, "Air Touch 64 diagnostic", state.log.joinToString("\n")) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("COPIAR DIAGNÓSTICO") }
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(4.dp)) {
            Text("VOLTAR AO INÍCIO")
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextMonospace, fontFamily = FontFamily.Monospace, softWrap = true)
    }
}

private fun copyToClipboard(context: android.content.Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
}
