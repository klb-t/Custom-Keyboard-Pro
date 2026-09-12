package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.PresentationMode
import com.example.ui.kb.BuiltinThemes
import com.example.ui.kb.ThemeStore

@Composable
fun AppearanceScreen(settings: Settings) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection("Theme") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                ThemeStore.allThemes(settings).forEach { theme ->
                    Column(
                        modifier = Modifier
                            .padding(4.dp)
                            .clickable { SettingsStore.update { it.copy(themeId = theme.id) } },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(theme.background, RoundedCornerShape(10.dp))
                                .border(
                                    if (settings.themeId == theme.id) 3.dp else 1.dp,
                                    if (settings.themeId == theme.id) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(24.dp, 16.dp)
                                    .background(theme.keyBackground, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(theme.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            InfoRow("A theme is just a set of colours. Export or import one under About & help.")
        }

        SettingsSection("Shape") {
            ChoiceRow(
                label = "Presentation",
                description = "Full width, shifted to one side, split down the middle, or " +
                    "a floating panel you can drag anywhere.",
                options = listOf(
                    PresentationMode.NORMAL,
                    PresentationMode.ONE_HANDED_LEFT,
                    PresentationMode.ONE_HANDED_RIGHT,
                    PresentationMode.SPLIT,
                    PresentationMode.FLOATING
                ),
                selected = settings.presentation,
                optionLabel = { mode ->
                    when (mode) {
                        PresentationMode.NORMAL -> "Full width"
                        PresentationMode.ONE_HANDED_LEFT -> "One-handed, left"
                        PresentationMode.ONE_HANDED_RIGHT -> "One-handed, right"
                        PresentationMode.SPLIT -> "Split"
                        PresentationMode.FLOATING -> "Floating"
                        PresentationMode.CYCLE -> "Cycle"
                    }
                },
                onSelect = { mode -> SettingsStore.update { it.copy(presentation = mode) } }
            )
            Divider()
            SliderRow(
                label = "Height (portrait)",
                description = "Share of the screen the keys take up.",
                value = settings.heightPortrait,
                range = 0.15f..0.7f,
                format = { it.asPercent() },
                onChange = { v -> SettingsStore.update { it.copy(heightPortrait = v) } }
            )
            Divider()
            SwitchRow(
                label = "Separate height in landscape",
                description = "Landscape has much less vertical room, so it usually wants " +
                    "a different number.",
                checked = settings.separateLandscapeSize,
                onChange = { on -> SettingsStore.update { it.copy(separateLandscapeSize = on) } }
            )
            if (settings.separateLandscapeSize) {
                SliderRow(
                    label = "Height (landscape)",
                    value = settings.heightLandscape,
                    range = 0.25f..0.9f,
                    format = { it.asPercent() },
                    onChange = { v -> SettingsStore.update { it.copy(heightLandscape = v) } }
                )
            }
            Divider()
            SliderRow(
                label = "Width",
                value = settings.widthFraction,
                range = 0.4f..1f,
                format = { it.asPercent() },
                onChange = { v -> SettingsStore.update { it.copy(widthFraction = v) } }
            )
            if (settings.presentation == PresentationMode.SPLIT) {
                SliderRow(
                    label = "Split gap",
                    description = "How far apart the two halves sit.",
                    value = settings.splitGapFraction,
                    range = 0.02f..0.4f,
                    format = { it.asPercent() },
                    onChange = { v -> SettingsStore.update { it.copy(splitGapFraction = v) } }
                )
            }
            Divider()
            SliderRow(
                label = "Space below the keys",
                description = "Useful on phones with a gesture bar that gets in the way.",
                value = settings.bottomPaddingDp,
                range = 0f..64f,
                format = { "${it.toInt()} dp" },
                onChange = { v -> SettingsStore.update { it.copy(bottomPaddingDp = v) } }
            )
        }

        SettingsSection("Keys") {
            SliderRow(
                label = "Gap between keys",
                value = settings.keyGapDp,
                range = 0f..12f,
                format = { "${it.toInt()} dp" },
                onChange = { v -> SettingsStore.update { it.copy(keyGapDp = v) } }
            )
            Divider()
            SliderRow(
                label = "Corner rounding",
                value = settings.keyCornerDp,
                range = 0f..26f,
                format = { "${it.toInt()} dp" },
                onChange = { v -> SettingsStore.update { it.copy(keyCornerDp = v) } }
            )
            Divider()
            SliderRow(
                label = "Label size",
                value = settings.keyTextScale,
                range = 0.6f..1.8f,
                format = { "%.1f×".format(it) },
                onChange = { v -> SettingsStore.update { it.copy(keyTextScale = v) } }
            )
            Divider()
            SwitchRow(
                label = "Show the key you are pressing",
                description = "A preview above your finger, so the letter is not hidden by it.",
                checked = settings.keyPreviewPopup,
                onChange = { on -> SettingsStore.update { it.copy(keyPreviewPopup = on) } }
            )
            if (settings.expertMode) {
                Divider()
                SliderRow(
                    label = "Opacity",
                    description = "Below 100% you can see what is behind the keyboard. " +
                        "Below about 60% the labels get hard to read.",
                    value = settings.keyboardOpacity,
                    range = 0.25f..1f,
                    format = { it.asPercent() },
                    onChange = { v -> SettingsStore.update { it.copy(keyboardOpacity = v) } }
                )
                Divider()
                SliderRow(
                    label = "Side padding",
                    value = settings.sidePaddingDp,
                    range = 0f..24f,
                    format = { "${it.toInt()} dp" },
                    onChange = { v -> SettingsStore.update { it.copy(sidePaddingDp = v) } }
                )
            }
        }

        SettingsSection("Feedback") {
            SwitchRow(
                label = "Vibrate on each key",
                checked = settings.hapticEnabled,
                onChange = { on -> SettingsStore.update { it.copy(hapticEnabled = on) } }
            )
            if (settings.hapticEnabled) {
                SliderRow(
                    label = "Vibration length",
                    value = settings.hapticMs.toFloat(),
                    range = 1f..80f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(hapticMs = v.toInt()) } }
                )
                SliderRow(
                    label = "Vibration strength",
                    description = "Ignored on devices without an amplitude-controlled motor.",
                    value = settings.hapticAmplitude.toFloat(),
                    range = 1f..255f,
                    format = { "${(it / 255f * 100).toInt()}%" },
                    onChange = { v -> SettingsStore.update { it.copy(hapticAmplitude = v.toInt()) } }
                )
            }
            Divider()
            SwitchRow(
                label = "Key click sound",
                description = "Uses the system keypress sounds, so it respects silent mode.",
                checked = settings.soundEnabled,
                onChange = { on -> SettingsStore.update { it.copy(soundEnabled = on) } }
            )
            if (settings.soundEnabled) {
                SliderRow(
                    label = "Click volume",
                    value = settings.soundVolume,
                    range = 0f..1f,
                    format = { it.asPercent() },
                    onChange = { v -> SettingsStore.update { it.copy(soundVolume = v) } }
                )
            }
        }

        SettingsSection(
            title = "Indicator lamps",
            subtitle = "The lit dots on modifier keys, and an optional status row above them."
        ) {
            SwitchRow(
                label = "Light up modifier keys",
                description = "Shows whether Shift, Ctrl and Alt are held, one-shot or locked.",
                checked = settings.indicatorsEnabled,
                onChange = { on -> SettingsStore.update { it.copy(indicatorsEnabled = on) } }
            )
            Divider()
            SwitchRow(
                label = "Status row",
                description = "A thin strip of lamps: caps, num and scroll lock, live " +
                    "modifier state, microphone and model activity, current layer.",
                checked = settings.indicatorStripVisible,
                onChange = { on -> SettingsStore.update { it.copy(indicatorStripVisible = on) } }
            )
        }

        if (settings.expertMode) {
            SettingsSection("Floating panel") {
                SliderRow(
                    label = "Width",
                    value = settings.floatingWidthDp,
                    range = 220f..520f,
                    format = { "${it.toInt()} dp" },
                    onChange = { v -> SettingsStore.update { it.copy(floatingWidthDp = v) } }
                )
                InfoRow(
                    "Position is set by dragging the panel's handle. Touches outside the " +
                        "panel go through to the app underneath."
                )
            }
        }
    }
}
