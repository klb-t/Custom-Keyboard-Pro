package com.example.core.caps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat

/**
 * What an ability needs before it can do anything.
 *
 * Three kinds, because the user meets them in three different ways and lumping them
 * together produces a screen that cannot tell somebody what to actually do next.
 */
sealed interface Need {
    /** Works on any phone, with nothing granted. The floor this app never drops below. */
    data object Nothing : Need

    /** An ordinary runtime permission: a dialog, one tap, reversible. */
    data class Permission(val name: String) : Need

    /**
     * A special access granted on a system settings screen rather than in a dialog.
     *
     * Kept apart from [Permission] because these cannot be requested — the app can only
     * open the screen and explain, and the difference matters to anybody writing the
     * text that has to explain it.
     */
    data class SpecialAccess(val settingsAction: String, val where: String) : Need

    /**
     * Any one of several grants will do, each giving the ability in its own way.
     * Listed in order of preference — the first gives the most.
     */
    data class AnyOf(val options: List<Need>) : Need
}

/** Whether this build can do a thing at all, and whether it is allowed to right now. */
enum class Availability {
    /** Available and permitted. */
    ON,

    /** This build can do it; the user has not granted what it needs. */
    OFF,

    /** Not built yet. Listed anyway, because a gap nobody wrote down is a gap nobody fills. */
    ABSENT
}

/**
 * One thing the keyboard can do, what it needs, and what happens when it cannot.
 *
 * [without] is not documentation. It is the load-bearing field: this app is meant to
 * handle every kind of input and output, which means almost every special permission
 * on the platform buys it something — and it is also meant to work with none of them
 * granted, doing less. Those two goals only stay compatible if every ability names its
 * own reduced form, and a test holds every entry to having one.
 */
data class Ability(
    val id: String,
    val label: String,
    /** What the user gets when this is on. */
    val gives: String,
    /** What happens instead when it is off. Never "the app stops working". */
    val without: String,
    val needs: Need,
    /** False while the ability is described but not yet implemented. */
    val built: Boolean = true
)

/**
 * Everything the keyboard could do, granted or not, built or not.
 *
 * Data rather than scattered permission checks, for the reason everything else here is
 * data: a feature should ask "can I do this?" and get an answer that accounts for the
 * permission, the build, and the fallback at once. Asking `checkSelfPermission` at the
 * point of use gets an answer to a narrower question and no plan for the other case.
 *
 * It is also the only honest way to answer "what does this app do?", because the true
 * answer depends on what the person has granted — and a screen built from this can say
 * so, line by line, instead of a feature failing silently later.
 */
object Abilities {

    const val DICTATION = "dictation"
    const val SCREENSHOT_TEXT = "screenshot_text"
    const val BACKGROUND_RESULTS = "background_results"
    const val DRAW_OVER_APPS = "draw_over_apps"
    const val READ_OTHER_APPS = "read_other_apps"
    const val ACT_IN_OTHER_APPS = "act_in_other_apps"
    const val POINTER = "pointer"
    const val CAPTURE_PLAYBACK = "capture_playback"
    const val POCKET_LOCK = "pocket_lock"

    val ALL: List<Ability> = listOf(
        Ability(
            id = DICTATION,
            label = "Dictation",
            gives = "Speak instead of typing, on this phone or through a provider.",
            without = "Type. Text pasted or converted from a recording still works, " +
                "because that reads a file rather than the microphone.",
            needs = Need.Permission(android.Manifest.permission.RECORD_AUDIO)
        ),
        Ability(
            id = SCREENSHOT_TEXT,
            label = "Text out of a screenshot you just took",
            gives = "Take a screenshot and the keyboard offers its text without you " +
                "finding the file.",
            without = "Copy the picture and paste it — the same reading, one step more.",
            needs = Need.Permission("android.permission.READ_MEDIA_IMAGES"),
            built = false
        ),
        Ability(
            id = BACKGROUND_RESULTS,
            label = "Results after you have closed the keyboard",
            gives = "A long transcription finishes and tells you, rather than being " +
                "lost when the keyboard goes away.",
            without = "Results appear on the keyboard's own strip while it is open. " +
                "Something that finishes after you close it is dropped.",
            needs = Need.Permission("android.permission.POST_NOTIFICATIONS"),
            built = false
        ),
        Ability(
            id = DRAW_OVER_APPS,
            label = "Marks on the text you are writing",
            gives = "Uncertain words coloured where they actually are, in any app.",
            without = "Colour is sent as formatting with the text, which plain text " +
                "fields keep and Flutter, WebView and Compose fields quietly drop. " +
                "The suggestion strip works either way.",
            needs = Need.SpecialAccess(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "Settings › Apps › Special access › Display over other apps"
            ),
            built = false
        ),
        Ability(
            id = READ_OTHER_APPS,
            label = "Seeing what is on screen",
            gives = "The keyboard can read the post, the message or the page you are " +
                "replying to, so a rewrite or a reply knows what it is about.",
            without = "The keyboard sees only the field you are typing in, which is " +
                "what an input method is given. Everything typed still works.",
            needs = Need.SpecialAccess(
                AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS,
                "Settings › Accessibility"
            )
        ),
        Ability(
            id = ACT_IN_OTHER_APPS,
            label = "Acting in other apps",
            gives = "Select many posts in a feed, tick a list of items, repeat an " +
                "action down a page — in a browser or an app alike.",
            without = "Nothing of the sort. There is no narrower permission that " +
                "allows it: an input method can reach the text field and nothing else.",
            needs = Need.SpecialAccess(
                AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS,
                "Settings › Accessibility"
            )
        ),
        Ability(
            id = POINTER,
            label = "A pointer for remote desktops",
            gives = "Move and click a cursor from the keyboard, for a remote desktop " +
                "session where the mouse is the thing you are missing.",
            without = "The trackpad panel moves the text cursor instead, arrow keys still " +
                "arrive, and the remote desktop client's own touch mode still works.",
            needs = Need.SpecialAccess(
                AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS,
                "Settings › Accessibility"
            )
        ),
        Ability(
            id = POCKET_LOCK,
            label = "Pocket lock",
            gives = "Touch and volume keys locked, screen black if you like, while a talking " +
                "app keeps running — so it can go in a pocket.",
            without = "The power button still switches the screen off, and apps that keep " +
                "talking with the screen off need nothing more. Those that stop cannot be " +
                "kept going with touch locked.",
            needs = Need.AnyOf(
                listOf(
                    Need.SpecialAccess(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS, "Settings › Accessibility"),
                    Need.SpecialAccess(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "Settings › Apps › Special access › Display over other apps"
                    )
                )
            )
        ),
        Ability(
            id = CAPTURE_PLAYBACK,
            label = "Transcribing what is playing",
            gives = "Take the audio a call or a video is playing and turn it into text.",
            without = "Copy an audio file and paste it — that already gives a " +
                "transcript. Apps are also free to refuse capture, and most do.",
            needs = Need.SpecialAccess(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "asked each time, like a screen recording"
            ),
            built = false
        )
    )

    fun byId(id: String): Ability? = ALL.firstOrNull { it.id == id }

    /**
     * Whether an ability can run right now.
     *
     * A [Need.SpecialAccess] is answered by whoever actually grants it: for
     * accessibility, whether our own service is switched on in the system's list;
     * for anything not built yet, [Availability.OFF] rather than a guess.
     */
    fun availability(context: Context, ability: Ability): Availability = when {
        !ability.built -> Availability.ABSENT
        else -> when (val need = ability.needs) {
            is Need.Nothing -> Availability.ON
            is Need.Permission ->
                if (ContextCompat.checkSelfPermission(context, need.name) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) Availability.ON else Availability.OFF
            else -> if (granted(context, need)) Availability.ON else Availability.OFF
        }
    }

    private fun granted(context: Context, need: Need): Boolean = when (need) {
        is Need.Nothing -> true
        is Need.Permission -> ContextCompat.checkSelfPermission(context, need.name) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        is Need.SpecialAccess -> when (need.settingsAction) {
            AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS -> com.example.io.IoAccessibilityService.isEnabled(context)
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION ->
                android.os.Build.VERSION.SDK_INT < 23 || AndroidSettings.canDrawOverlays(context)
            else -> false
        }
        is Need.AnyOf -> need.options.any { granted(context, it) }
    }

    fun isOn(context: Context, id: String): Boolean =
        byId(id)?.let { availability(context, it) == Availability.ON } ?: false

    /** Where to send somebody who wants to grant this, or null when it is a dialog. */
    fun settingsIntent(context: Context, ability: Ability): Intent? {
        val need = when (val n = ability.needs) {
            is Need.SpecialAccess -> n
            is Need.AnyOf -> n.options.filterIsInstance<Need.SpecialAccess>().firstOrNull()
            else -> null
        } ?: return null
        return Intent(need.settingsAction).apply {
            if (need.settingsAction == AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION ||
                need.settingsAction == AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS
            ) {
                data = Uri.parse("package:${context.packageName}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
