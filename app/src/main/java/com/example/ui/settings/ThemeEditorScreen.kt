package com.example.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.hitmap.HitmapExtractor
import com.example.ui.kb.BuiltinThemes
import com.example.ui.kb.KeyboardTheme
import com.example.ui.kb.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editing every colour the keyboard draws with, alpha included.
 *
 * The alpha channel is the point rather than a detail: with it, "keys you can see
 * through", "a panel that isn't there", "labels that stay solid over glass" are all
 * just colours, savable and shareable as a theme, instead of features somebody has to
 * add one by one.
 *
 * Three ways in, because people arrive differently: adjust a built-in, describe what
 * you want and let a model write it, or hand it a photo and take the palette from
 * that.
 */
@Composable
fun ThemeEditorScreen(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var draft by remember(settings.themeId) { mutableStateOf(ThemeStore.resolve(settings)) }
    var name by remember(settings.themeId) { mutableStateOf(draft.name) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wish by remember { mutableStateOf("") }
    var showJson by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = context.contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it)
                    } ?: error("That file could not be read as an image.")
                    HitmapExtractor.extractColorRegions(
                        source = bitmap,
                        tolerance = 40,
                        minPixelFraction = 0.002f,
                        includeExtremes = true,
                        maxRegions = 24
                    )
                }
            }
            result.fold(
                onSuccess = { regions ->
                    if (regions.isEmpty()) {
                        status = "No usable colours came out of that image."
                    } else {
                        draft = paletteFrom(regions.map { Color(it.color) }, draft)
                        status = "Palette taken from the image. Adjust anything, then save."
                    }
                },
                onFailure = { status = it.message }
            )
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Preview",
            subtitle = "Drawn with the theme as it stands, over a checkerboard so " +
                "transparency reads as transparency."
        ) {
            ThemePreview(draft, settings)
        }

        SettingsSection("Start from") {
            ChoiceRow(
                label = "Theme",
                description = "Built-in themes and any you saved.",
                options = ThemeStore.allThemes(settings),
                selected = ThemeStore.allThemes(settings).firstOrNull { it.id == draft.id } ?: draft,
                optionLabel = { it.name },
                onSelect = {
                    draft = it
                    name = it.name
                }
            )
            Divider()
            TextRow(
                label = "Name",
                value = name,
                onChange = { name = it }
            )
            Divider()
            ActionRow(
                "Use this theme now",
                "Saves it under its own id and switches the keyboard to it",
                onClick = {
                    val id = if (draft.id in BuiltinThemes.ALL.map { it.id })
                        ThemeStore.freshId(name) else draft.id
                    val saved = draft.copy(id = id, name = name.ifBlank { id })
                    ThemeStore.save(saved)
                    SettingsStore.update { it.copy(themeId = id) }
                    draft = saved
                    status = "Saved and applied."
                }
            )
            if (draft.id !in BuiltinThemes.ALL.map { it.id }) {
                Divider()
                ActionRow(
                    "Delete this theme",
                    "Built-in themes cannot be deleted; this one can",
                    onClick = {
                        ThemeStore.delete(draft.id)
                        draft = BuiltinThemes.DARK
                        name = draft.name
                        status = "Deleted."
                    }
                )
            }
            status?.let { InfoRow(it) }
        }

        SettingsSection(
            title = "Transparency, quickly",
            subtitle = "The three that between them make the keyboard see-through, " +
                "without hunting through twenty colours."
        ) {
            OpacityShortcut("Panel behind the keys", draft.background) {
                draft = draft.copy(background = it)
            }
            Divider()
            OpacityShortcut("Key faces", draft.keyBackground) {
                draft = draft.copy(
                    keyBackground = it,
                    keySpecialBackground = draft.keySpecialBackground.copy(alpha = it.alpha),
                    keyModifierBackground = draft.keyModifierBackground.copy(alpha = it.alpha)
                )
            }
            Divider()
            OpacityShortcut("Key labels", draft.keyText) {
                draft = draft.copy(keyText = it, keySpecialText = draft.keySpecialText.copy(alpha = it.alpha))
            }
            Divider()
            InfoRow(
                "Glass keys are hard to aim at with no edges. Turning on a key outline " +
                    "in Size, shape & theme gives them back an edge without giving them " +
                    "back a face."
            )
        }

        SettingsSection(
            title = "Every colour",
            subtitle = "${ThemeStore.FIELDS.size} of them, each with its own opacity."
        ) {
            ThemeStore.FIELDS.forEachIndexed { index, field ->
                if (index > 0) Divider()
                ColorRow(
                    label = field.label,
                    description = field.help,
                    argb = ThemeStore.argbOf(field.get(draft)),
                    onChange = { draft = field.set(draft, ThemeStore.colorOf(it)) }
                )
            }
        }

        SettingsSection(
            title = "Describe the theme you want",
            subtitle = "The model writes the colours; you keep or discard them."
        ) {
            TextRow(
                label = "What should it look like?",
                placeholder = "warm paper, brown ink, keys barely there",
                value = wish,
                singleLine = false,
                minLines = 2,
                onChange = { wish = it }
            )
            Button(
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        generateTheme(wish, draft, settings).fold(
                            onSuccess = {
                                draft = it
                                name = it.name
                                status = "Generated. Nothing is saved until you say so."
                            },
                            onFailure = { status = it.message ?: "The model could not be reached." }
                        )
                        busy = false
                    }
                },
                enabled = !busy && wish.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(if (busy) "Working…" else "Generate a theme") }
        }

        SettingsSection(
            title = "Take the palette from a picture",
            subtitle = "A wallpaper, a photo, a screenshot of a keyboard you like."
        ) {
            ActionRow(
                "Choose an image",
                "Colours are read out of it and mapped onto the theme",
                onClick = { imagePicker.launch("image/*") }
            )
        }

        SettingsSection("Theme as JSON") {
            ActionRow(
                if (showJson) "Hide the JSON" else "Show the JSON",
                "Copy it out, paste one in, keep it in a file",
                onClick = { showJson = !showJson }
            )
            if (showJson) {
                TextRow(
                    label = "This theme",
                    value = draft.toJson().toString(2),
                    singleLine = false,
                    minLines = 10,
                    onChange = { }
                )
                Divider()
                TextRow(
                    label = "Paste a theme to load it",
                    value = importText,
                    singleLine = false,
                    minLines = 4,
                    onChange = { importText = it }
                )
                ActionRow(
                    "Load pasted theme",
                    onClick = {
                        ThemeStore.parseOne(importText, draft).fold(
                            onSuccess = {
                                draft = it
                                name = it.name
                                status = "Loaded. Not saved yet."
                            },
                            onFailure = { status = "Could not read that: ${it.message}" }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OpacityShortcut(label: String, color: Color, onChange: (Color) -> Unit) {
    SliderRow(
        label = label,
        value = color.alpha,
        range = 0f..1f,
        format = { "${(it * 100).toInt()}%" },
        onChange = { onChange(color.copy(alpha = it)) }
    )
}

/**
 * A miniature keyboard, over a checkerboard.
 *
 * The checkerboard is not decoration: on a solid background a half-transparent key
 * looks like a slightly different colour, and the whole point of this screen is to
 * make transparency something you can see while you set it.
 */
@Composable
private fun ThemePreview(theme: KeyboardTheme, settings: Settings) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(16.dp)
    ) {
        Checkerboard(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .background(theme.background, RoundedCornerShape(8.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(22.dp).background(theme.stripBackground, RoundedCornerShape(4.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "  suggestion",
                    color = theme.stripText,
                    fontSize = 11.sp
                )
                Text(
                    "   from the model",
                    color = theme.stripAiText,
                    fontSize = 11.sp
                )
            }
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { row ->
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    row.forEach { ch ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(theme.keyBackground, RoundedCornerShape(4.dp))
                                .then(
                                    if (settings.keyBorderWidthDp > 0f)
                                        Modifier.border(
                                            settings.keyBorderWidthDp.dp,
                                            theme.keyBorder,
                                            RoundedCornerShape(4.dp)
                                        )
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(ch.toString(), color = theme.keyText, fontSize = 12.sp)
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
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(12) { col ->
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

/**
 * Maps colours pulled out of an image onto theme roles.
 *
 * Deliberately crude and predictable: darkest becomes the panel, the next becomes the
 * keys, the most saturated becomes the accent, and text is whichever of black or
 * white actually contrasts. A cleverer mapping that occasionally produces an
 * unreadable keyboard would be worse — and every colour it picks is editable on the
 * same screen.
 */
private fun paletteFrom(colors: List<Color>, base: KeyboardTheme): KeyboardTheme {
    if (colors.isEmpty()) return base
    fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    fun saturation(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        return if (max <= 0f) 0f else (max - min) / max
    }

    val sorted = colors.sortedBy { luminance(it) }
    val darkImage = luminance(sorted[sorted.size / 2]) < 0.5f

    val panel = if (darkImage) sorted.first() else sorted.last()
    val key = if (darkImage) sorted.getOrElse(sorted.size / 4) { panel }
    else sorted.getOrElse(sorted.size * 3 / 4) { panel }
    val accent = colors.maxByOrNull { saturation(it) } ?: key
    val onKey = if (luminance(key) < 0.5f) Color.White else Color.Black
    val onPanel = if (luminance(panel) < 0.5f) Color.White else Color.Black

    return base.copy(
        isDark = darkImage,
        background = panel,
        keyBackground = key,
        keySpecialBackground = key.copy(
            red = key.red * 0.85f, green = key.green * 0.85f, blue = key.blue * 0.85f
        ),
        keyModifierBackground = key.copy(
            red = key.red * 0.85f, green = key.green * 0.85f, blue = key.blue * 0.85f
        ),
        keyAccentBackground = accent,
        keyPressedBackground = accent.copy(alpha = 0.65f),
        keyActiveBackground = accent,
        keyText = onKey,
        keySpecialText = onKey.copy(alpha = 0.85f),
        keyHintText = onKey.copy(alpha = 0.55f),
        keyBorder = onKey.copy(alpha = 0.3f),
        stripBackground = panel,
        stripText = onPanel,
        popupBackground = key,
        popupText = onKey,
        popupSelected = accent
    )
}

private const val THEME_SYSTEM = """
You write colour themes for an Android keyboard.

Reply with ONE JSON object and nothing else. Every colour is "#AARRGGBB" — the first
pair is opacity, and you should use it: AA=00 is fully transparent, FF is solid.

Keys:
  name, dark (true/false), background, keyBackground, keySpecialBackground,
  keyModifierBackground, keyAccentBackground, keyPressedBackground,
  keyActiveBackground, keyText, keySpecialText, keyHintText, keyBorder,
  stripBackground, stripText, stripAiText, popupBackground, popupText,
  popupSelected, indicatorOn, indicatorOff

Rules:
- Key labels must stay legible against their key face. That constraint outranks the
  aesthetic every time: a beautiful theme nobody can read is a broken theme.
- When the request asks for transparency, put it in the alpha channel of the faces
  (background, keyBackground) and keep the text opaque.
- When you make key faces transparent, give keyBorder a visible alpha, or the keys
  lose their edges entirely.
"""

private suspend fun generateTheme(
    wish: String,
    base: KeyboardTheme,
    settings: Settings
): Result<KeyboardTheme> {
    val prompt = buildString {
        append("Current theme as a starting point:\n")
        append(base.toJson().toString(2))
        append("\n\nWhat the user wants:\n")
        append(wish.trim())
    }
    return AiClient.complete(
        config = AiConfig.from(settings, maxTokens = 900),
        systemPrompt = THEME_SYSTEM.trim(),
        userPrompt = prompt
    ).mapCatching { reply ->
        ThemeStore.parseOne(reply, base).getOrThrow().let {
            it.copy(id = ThemeStore.freshId(it.name.ifBlank { "generated" }, settings))
        }
    }
}
