package dev.akhil.freebuff.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.akhil.freebuff.core.CrashReporter
import dev.akhil.freebuff.data.prefs.ThemeMode
import dev.akhil.freebuff.design.FreebuffTheme
import dev.akhil.freebuff.ui.shell.AppShell

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read before starting the UI: if the previous launch died, the user gets
        // the actual reason on screen instead of another silent close.
        val previousCrash = runCatching { CrashReporter.lastCrash() }.getOrNull()

        try {
            setContent {
                val viewModel: AppViewModel = viewModel()
                val settings by viewModel.settings.collectAsState()
                val dark = when (settings.themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                }
                FreebuffTheme(darkTheme = dark) {
                    AppShell(viewModel)
                    previousCrash?.let { report -> CrashReportDialog(report) }
                }
            }
        } catch (error: Throwable) {
            // Compose itself failed to start. Render diagnostics with plain views
            // so the user sees why instead of the app disappearing.
            CrashReporter.record("activity.set-content", error)
            showPlainError(error)
        }
    }

    /** Last-resort UI that cannot fail: platform views only, no Compose. */
    private fun showPlainError(error: Throwable) {
        val message = buildString {
            appendLine("Freebuff Mobile could not start.")
            appendLine()
            appendLine("${error.javaClass.name}: ${error.message}")
            appendLine()
            error.stackTraceToString()
        }
        val view = TextView(this).apply {
            text = message
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 96, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(view) })
    }
}

/**
 * Shows the stack trace recorded by the previous launch. The text is selectable
 * and copyable so it can be sent on as-is; dismissing deletes the record.
 */
@Composable
private fun CrashReportDialog(report: String) {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {
            CrashReporter.clear()
            visible = false
        },
        title = { Text("Last launch closed unexpectedly") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "The previous start of Freebuff Mobile failed. This is the exact reason — " +
                        "copy it and send it on if you want it fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                CrashReporter.clear()
                visible = false
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReporter.clear()
                visible = false
            }) { Text("Dismiss") }
        },
    )
}
