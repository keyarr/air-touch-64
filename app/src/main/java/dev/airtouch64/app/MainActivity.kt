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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                ?: error("destino indisponível")
            Toast.makeText(this, "APK salvo", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Falha ao salvar: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps desconhecidos para este app", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Sem instalador disponível", Toast.LENGTH_LONG).show() }
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
        val horizontalPadding = if (maxWidth > 680.dp) (maxWidth - 640.dp) / 2 else 20.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun HomeScreen(state: UiState.Home, onPick: (Uri) -> Unit, onStart: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    ContentColumn {
        AppHeader()
        val source = state.source
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APK de origem", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Selecione o Adobe Photoshop Touch 1.3.7 (air.com.adobe.pstouchphone). O arquivo é verificado no cache local do aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                    Text(if (source == null) "Escolher APK" else "Trocar APK")
                }
                if (state.importing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Lendo e verificando APK…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (source != null) {
                    HorizontalDivider()
                    Text(source.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "SHA-256 ${source.inspection.sha256.take(16)}… · ${source.inspection.size / 1024 / 1024} MiB · ${source.inspection.entryCount} entradas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val supported = source.inspection.supported
                    Text(
                        if (supported) "APK compatível" else "APK incompatível com este bundle",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (supported) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                    if (!supported && source.inspection.missingEntries.isNotEmpty()) {
                        Text(
                            "Arquivos ausentes: ${source.inspection.missingEntries.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Patches incluídos", style = MaterialTheme.typography.titleMedium)
                    Text("${state.manifest.name} · versão ${state.manifest.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.manifest.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(step.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(step.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Text(
            "O APK de saída usa uma chave gerada neste aparelho. Para instalar, desinstale antes a versão assinada pela Adobe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onStart,
            enabled = source?.inspection?.supported == true && !state.importing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Aplicar patches") }
    }
}

@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), contentDescription = "Air Touch 64", modifier = Modifier.size(48.dp).clip(CircleShape))
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Air Touch 64", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Patcher local ARM64 para PS Touch", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunningScreen(state: UiState.Running) {
    ContentColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Column {
                Text("Aplicando patches", style = MaterialTheme.typography.titleLarge)
                Text("Não feche o app durante o processamento.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val total = state.manifest.steps.size
        val completeCount = state.completed.size
        LinearProgressIndicator(
            progress = {
                if (total == 0) 0f else ((completeCount + if (state.currentStep != null) 0.5f else 0f) / total).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.manifest.steps.forEach { step ->
                    val done = step.id in state.completed
                    val running = step.id == state.currentStep
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when { done -> "Concluído · ${step.name}"; running -> "Em andamento · ${step.name}"; else -> step.name },
                            style = MaterialTheme.typography.bodyLarge,
                            color = when { done -> MaterialTheme.colorScheme.tertiary; running -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                            fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(step.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Registro", style = MaterialTheme.typography.titleSmall)
            if (log.isEmpty()) Text("Aguardando…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.takeLast(40).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DoneScreen(result: PatchResult, onInstall: (File) -> Unit, onSave: (File) -> Unit, onReset: () -> Unit) {
    ContentColumn {
        Text("APK pronto", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
        Text("Os patches foram aplicados e o arquivo está pronto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Arquivo", result.output.name)
                LabeledValue("Tamanho", "${result.size / 1024 / 1024} MiB")
                LabeledValue("Entradas", result.entries.toString())
                LabeledValue("SHA-256", result.sha256.chunked(32).joinToString("\n"))
                LabeledValue("Assinatura", result.signerFingerprint.chunked(32).joinToString("\n"))
            }
        }
        Button(onClick = { onInstall(result.output) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Instalar") }
        OutlinedButton(onClick = { onSave(result.output) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Salvar APK") }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Voltar") }
    }
}

@Composable
private fun FailedScreen(state: UiState.Failed, onReset: () -> Unit) {
    ContentColumn {
        Text("Não foi possível concluir", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(state.message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (state.log.isNotEmpty()) LogCard(state.log)
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Voltar") }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = true)
    }
}
