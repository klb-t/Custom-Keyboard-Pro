package com.example.core.layout

import android.graphics.Bitmap
import android.util.Base64
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Writes layouts from prose or from a picture, using whichever model the user configured.
 *
 * The interesting half is the schema in [SCHEMA]: it is the same format the JSON editor
 * and the importer use, so a generated layout is not a special case — it can be read,
 * corrected and re-saved like any other.
 *
 * The previous version of this feature asked for a free-form JSON "description of the
 * keyboard" and then tried to parse it with an adapter expecting a different shape, so
 * it could not succeed. Here the prompt states the exact schema, and the result goes
 * through the same parser as everything else.
 */
object LayoutGenerator {

    private const val SYSTEM =
        "You write keyboard layouts as JSON for an Android input method. " +
            "Reply with one JSON object and nothing else: no prose, no markdown fence, " +
            "no comments."

    val SCHEMA: String = """
        The JSON object has this shape:

        {
          "id": "short_snake_case_id",
          "name": "Human readable name",
          "locale": "pl",                         // optional
          "defaultLayer": "base",
          "layers": {
            "base":   { "rows": [ ...rows... ] },
            "shift":  { "rows": [ ...rows... ] }, // optional
            "symbols":{ "rows": [ ...rows... ] }  // optional
          }
        }

        A row is { "height": 1.0, "keys": [ ...keys... ] }.
        A key is either a bare string (types itself) or an object:

        {
          "id": "q",
          "label": "q",                 // drawn on the key
          "hint": "1",                  // small corner glyph
          "icon": "backspace",          // one of: backspace enter space globe mic
                                        // settings close search shift capslock emoji
                                        // ai clipboard tab escape up down left right
                                        // undo redo layout menu
          "width": 1.0,                 // relative width within the row
          "style": "normal|special|modifier|accent",
          "repeat": true,               // auto-repeats while held
          "popup": ["ą","á","à"],       // long-press alternates
          "tap":       <action>,
          "longPress": <action>,
          "doubleTap": <action>,
          "swipe": { "up": <action>, "down": <action>, "left": <action>, "right": <action> },
          "indicators": [ { "source": "mod:shift", "style": "bar_bottom" } ]
        }

        An action is an object with a "type":
          {"type":"text","text":"a"}                      types literal text
          {"type":"key","code":"KEYCODE_TAB","meta":"ctrl"} sends a raw key event
          {"type":"modifier","kind":"shift|ctrl|alt|meta|fn|alt_gr",
           "mode":"one_shot|momentary|toggle|lock"}
          {"type":"layer","layer":"symbols","mode":"toggle"}
          {"type":"layout","target":"next"}               next layout in the rotation
          {"type":"language","target":"next"}
          {"type":"backspace","unit":"character|word|line"}
          {"type":"delete","unit":"character|word"}
          {"type":"enter"} {"type":"space"}
          {"type":"cursor","direction":"left|right|up|down|line_start|line_end",
           "unit":"character|word","select":false}
          {"type":"select","unit":"word|line|all"}
          {"type":"clipboard","op":"copy|cut|paste|history"}
          {"type":"undo"} {"type":"redo"}
          {"type":"panel","panel":"emoji|clipboard|voice|ai_tools|cursor|layout_picker"}
          {"type":"voice"} {"type":"ai","task":"fix"}
          {"type":"macro","steps":[ <action>, <action> ]}
          {"type":"dead","combining":"́","display":"´"}   dead key for accents
          {"type":"compose"} {"type":"unicode"}
          {"type":"presentation","mode":"one_handed_right|split|floating|cycle"}
          {"type":"hide"} {"type":"settings"} {"type":"switch_ime"}

        Indicator sources: "mod:shift" (active), "lock:shift" (locked), "layer:symbols",
        or a runtime flag: caps_lock, num_lock, scroll_lock, ai_busy, asr_listening,
        has_selection, password_field.
        Indicator styles: dot_top_left, dot_top_right, dot_bottom_left, dot_bottom_right,
        bar_top, bar_bottom, bar_left, bar_right, outline, fill, glow.

        Rules:
        - Include a working bottom row: a layout switch, space, and enter at minimum.
        - Give backspace "repeat": true.
        - Widths in a row are relative to each other; they need not sum to anything.
    """.trimIndent()

    /** Writes a layout from a description of how the keys should behave. */
    suspend fun fromDescription(config: AiConfig, description: String): Result<LayoutDef> =
        generate(config, buildPrompt(description), image = null)

    /**
     * Reads a layout off a screenshot.
     *
     * The model is asked for behaviour as well as glyphs, because the point is not to
     * copy a picture — it is to end up with a keyboard that does what the one in the
     * picture does.
     */
    suspend fun fromImage(config: AiConfig, bitmap: Bitmap, notes: String = ""): Result<LayoutDef> {
        val encoded = withContext(Dispatchers.Default) { bitmap.toJpegBase64() }
        val prompt = buildPrompt(
            "Recreate the keyboard in the attached screenshot.\n" +
                "Read the glyph on every key. Where a key is a function rather than a " +
                "character (shift, backspace, enter, symbols, language, microphone), bind " +
                "the matching action rather than typing its label. Infer sensible " +
                "long-press alternates for letters that have accented forms in the " +
                "language the keyboard appears to be for. If part of the keyboard is " +
                "obscured, reconstruct it from the layout family it belongs to.\n" +
                (if (notes.isBlank()) "" else "Additional instructions: $notes\n")
        )
        return generate(config, prompt, encoded)
    }

    private fun buildPrompt(instruction: String): String =
        "$instruction\n\n$SCHEMA"

    private suspend fun generate(config: AiConfig, prompt: String, image: String?): Result<LayoutDef> {
        val response = AiClient.complete(
            config = config.copy(maxTokens = maxOf(config.maxTokens, 4000), temperature = 0.2f),
            systemPrompt = SYSTEM,
            userPrompt = prompt,
            imageBase64 = image
        )
        return response.mapCatching { raw ->
            val layout = LayoutJson.parse(raw)
            // A model that omits bindings but supplies labels still produces a keyboard
            // that types; filling that in is cheaper than another round trip.
            LayoutAuthoring.bindLabelsAsText(layout)
        }
    }

    private fun Bitmap.toJpegBase64(quality: Int = 82): String {
        val stream = ByteArrayOutputStream()
        val scaled = if (width > 1280 || height > 1280) {
            val factor = 1280f / maxOf(width, height)
            Bitmap.createScaledBitmap(this, (width * factor).toInt(), (height * factor).toInt(), true)
        } else {
            this
        }
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
