package dev.akhil.freebuff.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.db.ModelRecordEntity
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.KeyValueRow
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.models.BuiltInCatalog
import dev.akhil.freebuff.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: AppViewModel) {
    val models by viewModel.models.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf<ModelRecordEntity?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri, "")
    }

    val llms = models.filter { it.kind == "LLM" }
    val voices = models.filter { it.kind != "LLM" }
    val installedBytes = models.filter { it.status == "INSTALLED" }.sumOf { it.sizeBytes }
    val device = viewModel.device

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("device") {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(AppIcons.Cpu)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("This device", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${device.totalRamMb} MB RAM · ${device.cpuCores} cores · ${Format.bytes(device.freeStorageBytes)} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Largest model this RAM can hold: ~${Format.bytes(device.recommendedMaxModelBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.installEverything() }, enabled = busy == null) {
                        Text(if (busy != null) busy!! else "Install everything")
                    }
                    TextButton(onClick = { viewModel.pauseDownloads() }) { Text("Pause") }
                    TextButton(onClick = { viewModel.resumeDownloads() }) { Text("Resume") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Total first-run download: ${Format.bytes(BuiltInCatalog.totalDownloadBytes)}. " +
                        "Installed now: ${Format.bytes(installedBytes)}. Downloads run sequentially and survive " +
                        "leaving the app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("llm-header") {
            SectionHeader(
                title = "Language models",
                subtitle = "One model is loaded at a time to protect memory",
                trailing = {
                    TextButton(onClick = { showAdd = true }) { Text("Add model") }
                },
            )
        }

        items(llms, key = { it.id }) { record ->
            ModelCard(
                record = record,
                isActive = active?.recordId == record.id,
                onDownload = { viewModel.installModel(record.id) },
                onLoad = { viewModel.loadModel(record.id) },
                onUnload = { viewModel.unloadModel() },
                onDelete = { viewModel.deleteModel(record.id) },
                onLicense = { showLicense = record },
            )
        }

        item("voice-header") {
            SectionHeader(
                title = "Voice components",
                subtitle = "Whisper for listening, Piper for Hindi speech",
            )
        }

        items(voices, key = { it.id }) { record ->
            ModelCard(
                record = record,
                isActive = false,
                onDownload = { viewModel.installModel(record.id) },
                onLoad = {},
                onUnload = {},
                onDelete = { viewModel.deleteModel(record.id) },
                onLicense = { showLicense = record },
            )
        }

        item("note") {
            SurfaceCard {
                Text("How installation works", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Files come straight from the Hugging Face CDN over HTTPS. Each download is resumed " +
                        "with a Range request, checked against its SHA-256 digest and byte length, then " +
                        "renamed into place atomically. Model architectures are read from the GGUF header " +
                        "before a file is offered for loading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAdd) {
        AddModelSheet(
            onDismiss = { showAdd = false },
            onImport = {
                showAdd = false
                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onHuggingFace = { repo, file, name ->
                viewModel.addHuggingFaceModel(repo, file, name)
                showAdd = false
            },
        )
    }

    showLicense?.let { record ->
        ModalBottomSheet(
            onDismissRequest = { showLicense = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
                Text(record.displayName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                KeyValueRow("License", record.license)
                KeyValueRow("Repository", record.repo ?: "Imported from device")
                KeyValueRow("File", record.fileName)
                KeyValueRow("Size", Format.bytes(record.sizeBytes))
                KeyValueRow("Quantization", record.quantization)
                KeyValueRow("Architecture", record.architecture)
                KeyValueRow("SHA-256", record.sha256 ?: "Not published upstream")
                if (record.notes != null) KeyValueRow("Notes", record.notes!!)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Licences belong to the model authors. The app ships no model weights and never " +
                        "claims authorship of them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    record: ModelRecordEntity,
    isActive: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onLicense: () -> Unit,
) {
    val installed = record.status == "INSTALLED"
    val downloading = record.status == "DOWNLOADING" || record.status == "VERIFYING" || record.status == "QUEUED"
    val progress = if (record.sizeBytes > 0) {
        (record.downloadedBytes.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val statusColor = when {
        installed -> MaterialTheme.colorScheme.primary
        record.status == "FAILED" || record.status == "INCOMPATIBLE" -> MaterialTheme.colorScheme.error
        downloading -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (record.kind == "LLM") AppIcons.Cube else AppIcons.Waveform)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (isActive) {
                StatusChip("Loaded", MaterialTheme.colorScheme.primary)
            } else {
                StatusChip(
                    text = when (record.status) {
                        "INSTALLED" -> "Installed"
                        "DOWNLOADING" -> Format.percent(progress)
                        "VERIFYING" -> "Verifying"
                        "QUEUED" -> "Queued"
                        "FAILED" -> "Failed"
                        "INCOMPATIBLE" -> "Unsupported"
                        "CANCELLED" -> "Paused"
                        else -> "Not installed"
                    },
                    color = statusColor,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(Format.bytes(record.sizeBytes), MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.quantization.isNotBlank()) {
                StatusChip(record.quantization, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ramMb = BuiltInCatalog.find(record.id)?.minRamMb
            if (ramMb != null) {
                StatusChip("~${ramMb} MB RAM", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (downloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (record.status == "QUEUED") 0f else progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Text(
                "${Format.bytes(record.downloadedBytes)} of ${Format.bytes(record.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (record.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                record.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                installed && record.kind == "LLM" -> {
                    Button(onClick = if (isActive) onUnload else onLoad) {
                        Text(if (isActive) "Unload" else "Load")
                    }
                }
                installed -> {
                    StatusChip("Ready", MaterialTheme.colorScheme.primary)
                }
                downloading -> {
                    TextButton(onClick = onDownload) { Text("Restart") }
                }
                else -> {
                    Button(onClick = onDownload) { Text("Download") }
                }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onLicense) { Text("Details") }
            Spacer(Modifier.weight(1f))
            if (installed || record.downloadedBytes > 0) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelSheet(
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onHuggingFace: (String, String, String) -> Unit,
) {
    var repo by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Add a model", style = MaterialTheme.typography.titleLarge)
            Text(
                "Only GGUF models whose architecture llama.cpp supports will be accepted. " +
                    "Nothing from a model repository is ever executed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Import a .gguf file from this device")
            }
            Spacer(Modifier.height(18.dp))
            Text("Or download from Hugging Face", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("Repository (owner/name)") },
                placeholder = { Text("ggml-org/Qwen3-0.6B-GGUF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = file,
                onValueChange = { file = it },
                label = { Text("Exact file name") },
                placeholder = { Text("Qwen3-0.6B-Q8_0.gguf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onHuggingFace(repo.trim(), file.trim(), name.trim()) },
                enabled = repo.contains("/") && file.endsWith(".gguf", true),
            ) { Text("Check and download") }
        }
    }
}
