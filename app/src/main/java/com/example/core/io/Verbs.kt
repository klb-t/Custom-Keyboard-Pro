package com.example.core.io

import com.example.core.caps.Abilities

/** One argument a verb takes, in the order it is written positionally. */
data class Param(val name: String, val help: String, val example: String = "")

/**
 * What a verb does, what it needs, and what it does when it cannot have that.
 *
 * [ability] names an entry in [Abilities] — the verb is only as available as that is.
 * [without] is the same load-bearing field the abilities have: a verb bound to a key
 * is still pressed when its permission is missing, and it has to do *something*
 * sensible then, even if that is only saying what it would have needed.
 */
data class VerbSpec(
    val id: String,
    /** Short enough to print on a key. */
    val glyph: String,
    val label: String,
    val help: String,
    val params: List<Param> = emptyList(),
    val ability: String? = null,
    val without: String,
    val group: String
) {
    /** Needs this app's accessibility service — the one grant most outputs go through. */
    val needsAccessibility: Boolean
        get() = ability == Abilities.ACT_IN_OTHER_APPS ||
            ability == Abilities.READ_OTHER_APPS ||
            ability == Abilities.POINTER
}

/**
 * Every verb the keyboard can perform outside the text field.
 *
 * The outputs half of the "any input to any output" engine: a key, a gesture, a macro
 * step — and later a sensor, a floating control or a network message — all reach the
 * world through a line naming one of these. What a verb *means* lives here, as data;
 * how it is carried out lives in the performer, which is the only part that touches
 * the platform. So the catalogue can be listed, searched, documented and checked by
 * tests without a phone, and a verb nobody has implemented yet fails loudly in one
 * test instead of silently on a key.
 */
object Verbs {

    const val GROUP_SYSTEM = "System"
    const val GROUP_TOUCH = "Touch"
    const val GROUP_SCREEN = "On screen"
    const val GROUP_MEDIA = "Media and device"
    const val GROUP_OPEN = "Open and share"
    const val GROUP_MACROS = "Macros"

    private val xy = listOf(
        Param("x", "Across the screen: 0–1 as a fraction, or pixels above 1.", "0.5"),
        Param("y", "Down the screen, the same way.", "0.8")
    )

    private val query = listOf(
        Param("text", "Visible text it contains (case ignored).", "Select all"),
        Param("id", "Its resource id, or the end of it.", "checkbox"),
        Param("desc", "Its content description — what a screen reader says.", "More options"),
        Param("index", "Which match, when several: 0 is the first.", "0")
    )

    private fun global(id: String, glyph: String, label: String, help: String, without: String) = VerbSpec(
        id = id, glyph = glyph, label = label, help = help,
        ability = Abilities.ACT_IN_OTHER_APPS, without = without, group = GROUP_SYSTEM
    )

    val ALL: List<VerbSpec> = listOf(
        global(
            "back", "◁", "Back", "The system Back, wherever you are.",
            "Sends the Back key to the app you are typing in, which most apps honour."
        ),
        global(
            "home", "○", "Home", "Go to the home screen.",
            "Says it needs accessibility; there is no other way for a keyboard to leave the app."
        ),
        global(
            "recents", "▢", "Recent apps", "The app switcher.",
            "Says it needs accessibility."
        ),
        global(
            "notifications", "⌄", "Notifications", "Pull down the notification shade.",
            "Says it needs accessibility."
        ),
        global(
            "quick_settings", "⚙", "Quick settings", "Open the quick settings tiles.",
            "Says it needs accessibility."
        ),
        global(
            "power_menu", "⏻", "Power menu", "The power / emergency menu.",
            "Says it needs accessibility."
        ),
        global(
            "lock", "🔒", "Lock screen", "Lock the phone now.",
            "Says it needs accessibility."
        ),
        global(
            "screenshot", "⎙", "Screenshot", "Take a screenshot, as the buttons would.",
            "Says it needs accessibility."
        ),
        global(
            "split_screen", "◫", "Split screen", "Put this app in split screen.",
            "Says it needs accessibility."
        ),
        global(
            "app_drawer", "⋮⋮", "All apps", "Open the list of all apps (Android 12+).",
            "Says it needs accessibility."
        ),

        VerbSpec(
            id = "tap", glyph = "☝", label = "Tap a point",
            help = "Touch the screen at a point, as a finger would.",
            params = xy, ability = Abilities.POINTER, group = GROUP_TOUCH,
            without = "Says it needs accessibility — a keyboard cannot touch another app."
        ),
        VerbSpec(
            id = "long_press", glyph = "☝…", label = "Long-press a point",
            help = "Touch and hold at a point.",
            params = xy + Param("ms", "How long to hold.", "600"),
            ability = Abilities.POINTER, group = GROUP_TOUCH,
            without = "Says it needs accessibility."
        ),
        VerbSpec(
            id = "swipe", glyph = "⇢", label = "Swipe",
            help = "Drag a finger from one point to another.",
            params = listOf(
                Param("x1", "Start, across.", "0.5"), Param("y1", "Start, down.", "0.7"),
                Param("x2", "End, across.", "0.5"), Param("y2", "End, down.", "0.3"),
                Param("ms", "How long the drag takes.", "250")
            ),
            ability = Abilities.POINTER, group = GROUP_TOUCH,
            without = "Says it needs accessibility."
        ),
        VerbSpec(
            id = "scroll", glyph = "⇕", label = "Scroll",
            help = "Scroll whatever list or page is on screen.",
            params = listOf(Param("direction", "down, up, left or right.", "down")),
            ability = Abilities.ACT_IN_OTHER_APPS, group = GROUP_TOUCH,
            without = "Sends Page Down / Page Up to the app, which scrolls most pages and lists."
        ),
        VerbSpec(
            id = "pointer", glyph = "➚", label = "Pointer",
            help = "Show or hide an on-screen pointer, moved from the trackpad panel — " +
                "a mouse for remote desktops and anything else made for one.",
            params = listOf(Param("state", "on, off or toggle.", "toggle")),
            ability = Abilities.POINTER, group = GROUP_TOUCH,
            without = "The trackpad panel moves the text cursor instead."
        ),

        VerbSpec(
            id = "click", glyph = "◉", label = "Press a control",
            help = "Find a button, checkbox or link on screen by what it says and press it.",
            params = query, ability = Abilities.ACT_IN_OTHER_APPS, group = GROUP_SCREEN,
            without = "Says it needs accessibility."
        ),
        VerbSpec(
            id = "long_click", glyph = "◉…", label = "Long-press a control",
            help = "The same, held — which is how most feeds start selecting.",
            params = query, ability = Abilities.ACT_IN_OTHER_APPS, group = GROUP_SCREEN,
            without = "Says it needs accessibility."
        ),
        VerbSpec(
            id = "sweep", glyph = "☑☑", label = "Select many",
            help = "Go down a list or feed doing the same thing to every item: tick every " +
                "checkbox, long-press the first item and tap the rest, or press every " +
                "control that matches — then scroll and carry on for as many pages as asked.",
            params = listOf(
                Param("mode", "checkboxes, longpress, tap or match.", "checkboxes"),
                Param("pages", "How many screens to go through.", "1")
            ) + query,
            ability = Abilities.ACT_IN_OTHER_APPS, group = GROUP_SCREEN,
            without = "Says it needs accessibility; there is no narrower permission that allows it."
        ),
        VerbSpec(
            id = "read_screen", glyph = "👁", label = "Read the screen",
            help = "Collect the text on screen — the post, the message, the page you are " +
                "answering — and put it on the clipboard, into the field, or in front of the AI.",
            params = listOf(Param("to", "clipboard, field or ai.", "clipboard")),
            ability = Abilities.READ_OTHER_APPS, group = GROUP_SCREEN,
            without = "Reads only the field you are typing in, which a keyboard is given anyway."
        ),

        VerbSpec(
            id = "media", glyph = "⏯", label = "Media",
            help = "Control whatever is playing.",
            params = listOf(
                Param("action", "play_pause, play, pause, next, previous, stop, rewind or forward.", "play_pause")
            ),
            group = GROUP_MEDIA,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "volume", glyph = "🔊", label = "Volume",
            help = "Turn a volume up, down or off.",
            params = listOf(
                Param("direction", "up, down, mute, unmute or toggle.", "up"),
                Param("stream", "music, ring, alarm, notification, call or system.", "music")
            ),
            group = GROUP_MEDIA,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "torch", glyph = "🔦", label = "Torch",
            help = "The camera flash as a light.",
            params = listOf(Param("state", "on, off or toggle.", "toggle")),
            group = GROUP_MEDIA,
            without = "Needs nothing; says so on a phone with no flash."
        ),
        VerbSpec(
            id = "vibrate", glyph = "〰", label = "Vibrate",
            help = "Buzz, for a macro that wants to say it finished.",
            params = listOf(Param("ms", "How long.", "40")),
            group = GROUP_MEDIA,
            without = "Needs nothing."
        ),

        VerbSpec(
            id = "open", glyph = "↗", label = "Open",
            help = "Open a link, or start an app by its package name.",
            params = listOf(Param("target", "A URL, or a package such as com.android.chrome.", "https://")),
            group = GROUP_OPEN,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "search", glyph = "🔍", label = "Search the web",
            help = "Search for the given words, or for the selection when none are given.",
            params = listOf(Param("query", "What to search for.", "")),
            group = GROUP_OPEN,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "share", glyph = "⇪", label = "Share",
            help = "Send the given text, or the selection, to another app.",
            params = listOf(Param("text", "What to share.", "")),
            group = GROUP_OPEN,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "pocket_lock", glyph = "🔐", label = "Pocket lock",
            help = "Lock touch and the volume keys while the app in front keeps running — " +
                "to put a talking app in a pocket. Unlocks with volume +, −, + or two " +
                "fingers held for two seconds (both changeable), never while covered.",
            params = listOf(
                Param("state", "on, off or toggle.", "toggle"),
                Param("mode", "touch (screen stays) or screen (goes black).", "screen")
            ),
            ability = Abilities.POCKET_LOCK, group = GROUP_SYSTEM,
            without = "Works with either accessibility or \"display over other apps\"; with " +
                "neither, says so and offers the screen that grants it."
        ),
        VerbSpec(
            id = "read_aloud", glyph = "🗣", label = "Read aloud",
            help = "Read text out loud with the phone's own voice: the selection, the whole field, " +
                "what was just written, the clipboard, the screen, or a whole page scrolled as it goes.",
            params = listOf(
                Param("source", "auto, selection, field, before, sentence, word, clipboard, screen, page or text.", "auto"),
                Param("text", "With source=text: what to say.", "")
            ),
            group = GROUP_MEDIA,
            without = "Needs nothing; the screen and a page need accessibility, and say so."
        ),
        VerbSpec(
            id = "read_control", glyph = "⏯", label = "Reading: pause, resume, stop",
            help = "Controls whatever is being read aloud. A headset button does the same.",
            params = listOf(Param("action", "toggle, pause, resume or stop.", "toggle")),
            group = GROUP_MEDIA,
            without = "Needs nothing; with nothing being read, says so."
        ),
        VerbSpec(
            id = "record", glyph = "⏺", label = "Record a macro",
            help = "Start or stop recording everything pressed on the keyboard — keys, " +
                "actions, pauses between actions on other apps — under a name, to play back later.",
            params = listOf(
                Param("state", "start, stop or toggle.", "toggle"),
                Param("name", "What to call it.", "last")
            ),
            group = GROUP_MACROS,
            without = "Needs nothing."
        ),
        VerbSpec(
            id = "play", glyph = "▶", label = "Play a macro",
            help = "Play back a recorded macro, once or several times.",
            params = listOf(
                Param("name", "Which one.", "last"),
                Param("times", "How many times in a row.", "1")
            ),
            group = GROUP_MACROS,
            without = "Needs nothing; the steps inside it need whatever they need."
        ),
        VerbSpec(
            id = "wait", glyph = "⏱", label = "Wait",
            help = "Pause a macro, so an app has time to open, load or animate before the next step.",
            params = listOf(Param("ms", "How long.", "500")),
            group = GROUP_MACROS,
            without = "Needs nothing; outside a macro it does nothing, which is what waiting is."
        ),
        VerbSpec(
            id = "system_settings", glyph = "⚙", label = "A settings screen",
            help = "Jump straight to one screen of the phone's settings.",
            params = listOf(
                Param(
                    "page",
                    "wifi, bluetooth, display, sound, battery, accessibility, keyboard, " +
                        "apps, location, network, notifications, date, language, storage, " +
                        "developer, or anything else — which opens the main settings.",
                    "wifi"
                )
            ),
            group = GROUP_OPEN,
            without = "Needs nothing."
        )
    )

    private val index = ALL.associateBy { it.id }

    fun byId(id: String): VerbSpec? = index[id.lowercase()]

    /** What a key bound to [command] shows when it has no label of its own. */
    fun glyphFor(command: Command): String = byId(command.verb)?.glyph ?: command.verb
}
