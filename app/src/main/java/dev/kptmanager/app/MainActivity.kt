package dev.kptmanager.app

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.kptmanager.engine.PatchResult
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PatchViewModel by viewModels()
    private var pendingSave: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF7AB8FF),
                secondary = Color(0xFF9AD1FF),
                background = Color(0xFF101418),
                surface = Color(0xFF181D23),
            )) {
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
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is UiState.Home -> HomeScreen(
                    state = current,
                    onPick = viewModel::importSource,
                    onStart = viewModel::start,
                )

                is UiState.Running -> RunningScreen(current)

                is UiState.Done -> DoneScreen(
                    result = current.result,
                    onInstall = onInstall,
                    onSave = onSave,
                    onReset = viewModel::reset,
                )

                is UiState.Failed -> FailedScreen(current, onReset = viewModel::reset)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: UiState.Home,
    onPick: (Uri) -> Unit,
    onStart: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("PS", fontWeight = FontWeight.Bold, color = Color(0xFF101418))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("PS Touch Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Patcher local do PS Touch ARM64",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val source = state.source
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APK de origem", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Escolha o APK do Adobe Photoshop Touch 1.3.7 (air.com.adobe.pstouchphone). O arquivo não é copiado para lugar nenhum além do cache local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                    Text(if (source == null) "Escolher APK" else "Trocar APK")
                }
                if (state.importing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Lendo e verificando…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (source != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(source.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "SHA-256 ${source.inspection.sha256.take(16)}…  ·  ${source.inspection.size / 1024 / 1024} MiB  ·  ${source.inspection.entryCount} entradas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val supported = source.inspection.supported
                    Text(
                        if (supported) "APK reconhecido" else "APK não suportado pelo bundle",
                        color = if (supported) Color(0xFF8BD68B) else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!supported && source.inspection.missingEntries.isNotEmpty()) {
                        Text(
                            "Faltando: ${source.inspection.missingEntries.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Patches", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.manifest.name} ${state.manifest.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.manifest.steps.forEach { step ->
                    Column {
                        Text(step.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            step.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            "O APK de saída é assinado com uma chave gerada neste aparelho. Ele não atualiza uma instalação assinada pela Adobe: desinstale a versão antiga antes de instalar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onStart,
            enabled = source != null && source.inspection.supported,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Aplicar patches")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RunningScreen(state: UiState.Running) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Aplicando patches…", style = MaterialTheme.typography.titleMedium)
        }
        state.manifest.steps.forEach { step ->
            val done = step.id in state.completed
            val running = step.id == state.currentStep
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            when {
                                done -> Color(0xFF8BD68B)
                                running -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    step.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done || running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E11)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.log.isEmpty()) {
                    Text("aguardando…", style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A8794))
                }
                state.log.takeLast(40).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB8C4CE),
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneScreen(
    result: PatchResult,
    onInstall: (File) -> Unit,
    onSave: (File) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("APK gerado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledValue("Arquivo", result.output.name)
                LabeledValue("Tamanho", "${result.size / 1024 / 1024} MiB")
                LabeledValue("Entradas", result.entries.toString())
                LabeledValue("SHA-256", result.sha256.chunked(32).joinToString("\n"))
                LabeledValue("Assinatura", result.signerFingerprint.chunked(47).joinToString("\n"))
            }
        }
        Button(onClick = { onInstall(result.output) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Instalar")
        }
        OutlinedButton(onClick = { onSave(result.output) }, modifier = Modifier.fillMaxWidth()) {
            Text("Salvar APK")
        }
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Voltar")
        }
    }
}

@Composable
private fun FailedScreen(state: UiState.Failed, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Falhou", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.log.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E11))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.log.takeLast(40).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFB8C4CE))
                    }
                }
            }
        }
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
