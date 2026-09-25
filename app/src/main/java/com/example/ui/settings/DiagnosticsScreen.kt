package com.example.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingsSchema
import com.example.core.layout.LayoutDoctor
import com.example.core.layout.LayoutRepository
import com.example.core.caps.Abilities
import com.example.core.caps.Availability
import com.example.core.caps.Need
import com.example.util.AppLogger

/**
 * The log, readable without a computer.
 *
 * This screen exists for exactly one situation: the keyboard failed and the only
 * tools at hand are this app and a phone. [AppLogger] already keeps a timestamped,
 * hierarchical trail of keyboard startup and a full stack trace for anything that
 * crashed the process — this is the way to actually read it, copy it, or hand it to
 * someone else, since there is no Logcat here.
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logs: SnapshotStateList<String> = AppLogger.logs

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        // The honest answer to "what does this app do?", which depends on what has
        // been granted. Here rather than buried with the permissions themselves,
        // because somebody reading this screen is somebody asking why something did
        // not happen — and a line saying what it does instead is the answer.
        SettingsSection(
            title = "What this build can do",
            subtitle = "The keyboard works with nothing granted. Each of these adds " +
                "something, and each says what happens without it."
        ) {
            Abilities.ALL.forEach { ability ->
                val availability = Abilities.availability(context, ability)
                StatusRow(
                    label = ability.label + when (availability) {
                        Availability.ON -> ""
                        Availability.OFF -> " — not granted"
                        Availability.ABSENT -> " — not built yet"
                    },
                    ok = availability == Availability.ON
                )
                InfoRow(
                    if (availability == Availability.ON) ability.gives
                    else "Without it: " + ability.without
                )
                if (availability == Availability.OFF) {
                    // Every screen that grants it — where there are several ways in,
                    // each is offered, since each gives something slightly different.
                    val screens = when (val n = ability.needs) {
                        is Need.SpecialAccess -> listOf(n)
                        is Need.AnyOf -> n.options.filterIsInstance<Need.SpecialAccess>()
                        else -> emptyList()
                    }
                    screens.forEach { need ->
                        ActionRow(
                            label = if (screens.size > 1) "Grant it this way" else "Open the settings screen for this",
                            description = need.where,
                            onClick = {
                                runCatching {
                                    val intent = android.content.Intent(need.settingsAction).apply {
                                        if (need.settingsAction == android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION ||
                                            need.settingsAction == android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS
                                        ) {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )
                    }
                }
                Divider()
            }
        }

        SettingsSection(
            title = "Diagnostics",
            subtitle = "Every entry the keyboard has logged since it was installed, " +
                "newest first — including a full stack trace for any crash. Nothing " +
                "here leaves the device unless you share it yourself."
        ) {
            val crashCount = logs.count { it.contains(" CRASH:") }
            InfoRow(
                if (logs.isEmpty()) "No log entries yet."
                else "${logs.size} entries" +
                    if (crashCount > 0) ", $crashCount of them crashes." else "."
            )
            ActionRow(
                "Copy everything",
                "Puts the whole log on the clipboard",
                onClick = { clipboard.setText(AnnotatedString(AppLogger.exportText())) }
            )
            Divider()
            ActionRow(
                "Share…",
                "Send the log as text, e.g. to an email or a chat",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "IO Matrix — diagnostics")
                        putExtra(Intent.EXTRA_TEXT, AppLogger.exportText())
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                }
            )
            Divider()
            ActionRow(
                "Clear log",
                "Cannot be undone",
                onClick = { AppLogger.clear() }
            )
        }

        SettingsSection(
            title = "Layout health",
            subtitle = "Layouts are data, and a model is allowed to write them — so " +
                "they can say things no compiler would have let through. This is every " +
                "layout on the device, checked. Anything listed here draws wrongly on " +
                "the keyboard with no other sign that it is wrong."
        ) {
            val layouts = LayoutRepository.all()
            val findings = layouts.map { it to LayoutDoctor.check(it) }.filter { it.second.isNotEmpty() }
            if (findings.isEmpty()) {
                InfoRow("All ${layouts.size} layouts check out.")
            } else {
                findings.forEach { (layout, problems) ->
                    InfoRow("${layout.name} (${layout.id}) — ${problems.size} problem" +
                        if (problems.size == 1) "" else "s")
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        problems.forEach { finding ->
                            Text(
                                "· $finding" +
                                    if (finding.repairable) "  — fixed automatically when saved" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (finding.severity == LayoutDoctor.Severity.BROKEN)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    Divider()
                }
            }
        }

        if (SettingsSchema.orphanedMetadata.isNotEmpty()) {
            SettingsSection(
                title = "Settings with mismatched descriptions",
                subtitle = "These names appear in the table that gives settings their " +
                    "labels and ranges, but no such setting exists. Harmless, except " +
                    "that whatever they were meant for is showing a generated label."
            ) {
                SettingsSchema.orphanedMetadata.forEach { InfoRow(it) }
            }
        }

        SettingsSection("Log") {
            if (logs.isEmpty()) {
                InfoRow("Nothing logged yet. Trigger the problem, then come back here.")
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    logs.forEach { entry ->
                        val isCrash = entry.contains(" CRASH:") || entry.contains(" - ") &&
                            entry.contains("Exception")
                        Text(
                            entry,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCrash) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        Divider()
                    }
                }
            }
        }
    }
}
