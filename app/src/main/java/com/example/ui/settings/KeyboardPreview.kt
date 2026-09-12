package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.config.Settings
import com.example.ui.kb.KeyboardTheme
import com.example.ui.kb.ThemeStore

/**
 * A miniature of the keyboard as the current settings would draw it.
 *
 * Worth its space because the settings it reflects are the ones nobody can predict
 * from a number: what 40% opacity looks like, whether a 12dp corner is too round,
 * whether the labels survive a transparent key face. Reading those off a slider means
 * leaving the screen, opening a text field, and coming back — which is enough friction
 * that most people simply never adjust them.
 *
 * It sits over a checkerboard for the same reason the theme editor's does: on a solid
 * background, a half-transparent key looks like a slightly different colour rather
 * than like transparency.
 */
@Composable
fun KeyboardPreview(
    settings: Settings,
    theme: KeyboardTheme = ThemeStore.resolve(settings),
    heightDp: Int = 150,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .padding(horizontal = 16.dp)
    ) {
        Checkerboard(Modifier.fillMaxSize())

        val panelAlpha = theme.background.alpha * settings.panelOpacity.coerceIn(0f, 1f)
        val keyAlpha = settings.keyOpacity.coerceIn(0f, 1f)
        val labelAlpha = settings.keyLabelOpacity.coerceIn(0f, 1f)
        val corner = settings.keyCornerDp.coerceIn(0f, 24f).dp
        val gap = (settings.keyGapDp / 2f).coerceIn(0f, 6f).dp

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = settings.sidePaddingDp.dp)
                .background(theme.background.copy(alpha = panelAlpha))
                .padding(vertical = 4.dp)
                .alpha(settings.keyboardOpacity.coerceIn(0.05f, 1f)),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            if (settings.suggestionsEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(theme.stripBackground.copy(alpha = panelAlpha)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "  suggestion   another   ",
                        color = theme.stripText.copy(alpha = labelAlpha),
                        fontSize = 9.sp
                    )
                }
            }

            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { row ->
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    row.forEach { ch ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(
                                    theme.keyBackground.copy(
                                        alpha = theme.keyBackground.alpha * keyAlpha
                                    ),
                                    RoundedCornerShape(corner)
                                )
                                .then(
                                    if (settings.keyBorderWidthDp > 0f)
                                        Modifier.border(
                                            settings.keyBorderWidthDp.dp,
                                            theme.keyBorder.copy(
                                                alpha = theme.keyBorder.alpha *
                                                    settings.keyBorderOpacity.coerceIn(0f, 1f)
                                            ),
                                            RoundedCornerShape(corner)
                                        )
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ch.toString(),
                                color = theme.keyText.copy(alpha = theme.keyText.alpha * labelAlpha),
                                fontSize = (11 * settings.keyTextScale).coerceIn(7f, 18f).sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    Column(modifier) {
        repeat(6) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(14) { col ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                if ((row + col) % 2 == 0) Color(0xFFB8BCC4) else Color(0xFF8C9199)
                            )
                    )
                }
            }
        }
    }
}

/** A one-line summary of what the keyboard currently looks like, for a section header. */
@Composable
fun PreviewCaption(settings: Settings) {
    val bits = buildList {
        add("${(settings.heightPortrait * 100).toInt()}% tall")
        if (settings.keyboardOpacity < 1f) add("${(settings.keyboardOpacity * 100).toInt()}% opaque")
        if (settings.panelOpacity < 1f) add("panel ${(settings.panelOpacity * 100).toInt()}%")
        if (settings.keyOpacity < 1f) add("keys ${(settings.keyOpacity * 100).toInt()}%")
        if (settings.keyBorderWidthDp > 0f) add("outlined")
    }
    Text(
        bits.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
