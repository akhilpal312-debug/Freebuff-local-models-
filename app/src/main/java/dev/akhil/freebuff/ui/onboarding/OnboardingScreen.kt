package dev.akhil.freebuff.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.core.DeviceInspector
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.models.BuiltInCatalog
import dev.akhil.freebuff.ui.AppViewModel

/**
 * First launch. Explains exactly what will be downloaded, how large it is, and
 * asks for consent before anything is fetched. Nothing is installed silently and
 * the user is never asked to type a command.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val device = viewModel.device
    val busy by viewModel.busy.collectAsState()
    var acknowledged by remember { mutableStateOf(false) }
    val storageOk = DeviceInspector.hasSufficientStorage(
        context,
        BuiltInCatalog.totalDownloadBytes,
    )

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(AppIcons.Sparkle, size = 46)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Freebuff Mobile", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "A private AI workstation · by Akhil",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "Everything runs on this phone",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "No account, no sign-in, no cloud. Your conversations, projects and recordings stay in " +
                "app-private storage, and backups are disabled for this app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        SurfaceCard {
            Text("This device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${device.totalRamMb} MB RAM · ${device.cpuCores} cores · ${Format.bytes(device.freeStorageBytes)} free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.totalRamMb < 3000) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "With ${device.totalRamMb} MB of RAM, use the smallest model and keep the context window " +
                        "small. Larger models may fail to load — the app will say so instead of pretending.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Components to download", style = MaterialTheme.typography.titleLarge)
        Text(
            "Sequential download, checked against SHA-256, resumable, and continued in the background.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        BuiltInCatalog.installOrder.forEach { spec ->
            SurfaceCard(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (spec.kind == dev.akhil.freebuff.models.AssetKind.LLM) AppIcons.Cube else AppIcons.Waveform, size = 34)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            spec.role,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(Format.bytes(spec.sizeBytes), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${spec.license} · ~${spec.minRamMb} MB RAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Total: ${Format.bytes(BuiltInCatalog.totalDownloadBytes)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    if (storageOk) "Storage ok" else "Not enough space",
                    if (storageOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Text("Microphone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Voice input is tap-to-talk only. There is no wake word and the microphone is released as " +
                    "soon as your sentence ends. Audio is transcribed on device and never uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.installEverything()
                viewModel.completeOnboarding()
            },
            enabled = storageOk && busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy != null) busy!! else "Download and install now")
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = { viewModel.completeOnboarding() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Set up later — I know my way around")
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Model licences belong to their authors: Gemma Terms of Use for Gemma 3, Apache-2.0 for the " +
                "Qwen models, MIT for whisper.cpp, and each Piper voice card for its own voice.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
