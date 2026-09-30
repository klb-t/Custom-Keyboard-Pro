package com.example.core.phone

import com.example.core.caps.Abilities
import com.example.core.io.Command
import com.example.core.io.Param
import com.example.core.io.VerbSpec
import com.example.core.io.Verbs

/** Android routes are data shared by keys, toolbox and the reviewed goal assistant. */
data class PhoneRoute(
    val id: String,
    val label: String,
    val action: String,
    val minimumApi: Int = 24,
    val packageScoped: Boolean = false,
    val explanation: String,
    val reference: String = "https://developer.android.com/reference/android/provider/Settings"
)

object PhoneCatalogue {
    val routes = listOf(
        PhoneRoute("usage", "App usage access", "android.settings.USAGE_ACCESS_SETTINGS", explanation = "Recent usage history requires a user grant; it is not a list of running processes."),
        PhoneRoute("overlay", "Display over other apps", "android.settings.action.MANAGE_OVERLAY_PERMISSION", packageScoped = true, explanation = "Shows the system grant screen. The user decides whether this app may draw over other apps."),
        PhoneRoute("write_settings", "Modify system settings", "android.settings.action.MANAGE_WRITE_SETTINGS", packageScoped = true, explanation = "Allows selected Settings.System values. It does not authorize writing Secure or Global settings."),
        PhoneRoute("accessibility", "Accessibility", "android.settings.ACCESSIBILITY_SETTINGS", explanation = "Only the enabled IO Matrix accessibility service can perform its audited navigation and screen operations."),
        PhoneRoute("notifications", "Notification listener access", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", explanation = "No notification-listener adapter exists in this build. This screen alone does not implement reading notifications."),
        PhoneRoute("battery", "Battery optimization", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", explanation = "Inspect the system list. No exemption is requested automatically."),
        PhoneRoute("exact_alarm", "Exact alarms", "android.settings.REQUEST_SCHEDULE_EXACT_ALARM", minimumApi = 31, packageScoped = true, explanation = "No exact-alarm scheduler exists in this build. No grant is requested on opening the toolbox."),
        PhoneRoute("all_files", "All files access", "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", minimumApi = 30, explanation = "Inspect-only: this build deliberately uses user-selected SAF documents and does not request MANAGE_EXTERNAL_STORAGE."),
        PhoneRoute("assistant", "Default assistant", "android.settings.VOICE_INPUT_SETTINGS", explanation = "Choose the default assistant through Android's role dialog where available. API 31+ has a reviewed foreground voice-session host; older versions retain ACTION_ASSIST. Selecting it grants no developer/process privileges and starts no recording."),
        PhoneRoute("autofill", "Autofill service", "android.settings.REQUEST_SET_AUTOFILL_SERVICE", minimumApi = 26, packageScoped = true, explanation = "Choose IO Matrix's existing authenticated vault Autofill service."),
        PhoneRoute("developer", "Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", explanation = "Navigation only. An ordinary app cannot enable developer mode, USB debugging or secure flags."),
        PhoneRoute("wireless_debug", "Wireless debugging", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", minimumApi = 30, explanation = "Open Developer options, then select Wireless debugging. Pairing requires a separately authorized ADB client; no privileged bridge is connected here."),
        PhoneRoute("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS", explanation = "The user changes Wi-Fi on the system screen; this app does not silently toggle it."),
        PhoneRoute("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS", explanation = "The user changes Bluetooth on the system screen."),
        PhoneRoute("display", "Display", "android.settings.DISPLAY_SETTINGS", explanation = "System display settings."),
        PhoneRoute("sound", "Sound", "android.settings.SOUND_SETTINGS", explanation = "System audio settings."),
        PhoneRoute("notification_settings", "Notification settings", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", minimumApi = 26, explanation = "Manage app notifications on the system screen; this is separate from granting a notification listener."),
        PhoneRoute("battery_saver", "Battery saver", "android.settings.BATTERY_SAVER_SETTINGS", explanation = "System battery-saver settings; this is separate from a battery-optimization exemption."),
        PhoneRoute("keyboard", "Keyboards", "android.settings.INPUT_METHOD_SETTINGS", explanation = "Choose and configure input methods."),
        PhoneRoute("apps", "Applications", "android.settings.APPLICATION_SETTINGS", explanation = "App management. Force-stop and other-app process control remain system/user operations."),
        PhoneRoute("location", "Location", "android.settings.LOCATION_SOURCE_SETTINGS", explanation = "Location state is controlled by the system/user."),
        PhoneRoute("network", "Network", "android.settings.WIRELESS_SETTINGS", explanation = "System network settings."),
        PhoneRoute("date", "Date and time", "android.settings.DATE_SETTINGS", explanation = "System date and time settings."),
        PhoneRoute("language", "Language", "android.settings.LOCALE_SETTINGS", explanation = "System language settings."),
        PhoneRoute("storage", "Storage", "android.settings.INTERNAL_STORAGE_SETTINGS", explanation = "System storage settings."),
        PhoneRoute("nfc", "NFC", "android.settings.NFC_SETTINGS", explanation = "System NFC settings."),
        PhoneRoute("mobile", "Mobile network", "android.settings.DATA_ROAMING_SETTINGS", explanation = "System mobile-network settings."),
        PhoneRoute("security", "Security", "android.settings.SECURITY_SETTINGS", explanation = "System security settings."),
        PhoneRoute("privacy", "Privacy", "android.settings.PRIVACY_SETTINGS", explanation = "System privacy settings."),
        PhoneRoute("settings", "System settings", "android.settings.SETTINGS", explanation = "The main system settings screen.")
    )
    val accessIds = setOf("usage", "overlay", "write_settings", "accessibility", "notifications", "battery", "exact_alarm", "all_files", "assistant", "autofill")
    private val aliases = mapOf("usage_access" to "usage", "notification_listener" to "notifications", "input" to "keyboard", "wireless" to "network", "time" to "date", "locale" to "language", "data" to "mobile", "" to "settings")
    fun route(id: String): PhoneRoute? {
        val key = id.trim().lowercase()
        return routes.firstOrNull { it.id == (aliases[key] ?: key) || it.action == id.trim() }
    }

    val verbs = listOf(
        spec("phone_tools", "🧰", "Phone toolbox", "Inspect capabilities, sensors, apps, access and actions. Opening it performs no sensor capture or grants.", listOf(Param("tab", "overview, sensors, apps, access or actions.", "overview"))),
        spec("phone_info", "ⓘ", "Phone diagnostics", "Read the chosen local diagnostic snapshot; Android restricts the visible process list.", listOf(Param("section", "device, memory, processes, apps, usage or access.", "device"))),
        spec("sensors", "◉", "Sensor inventory", "List all sensors actually reported by SensorManager, including multiple and vendor sensors; inventory is not permission to sample."),
        spec("sensor_monitor", "≋", "Monitor a sensor", "Open a foreground review with a bounded local sensor session. Press Start there; nothing is captured automatically.", listOf(Param("sensor", "Inventory key from the Sensors panel.", "s1.0.0"), Param("hz", "Requested maximum UI updates/s, 1–20.", "10"), Param("seconds", "Session duration, 1–300 seconds.", "30"))),
        spec("special_access", "⚿", "Special app access", "Open the selected system setup page; a dispatched settings activity is not a granted capability.", listOf(Param("target", "usage, overlay, write_settings, accessibility, notifications, battery, exact_alarm, all_files, assistant or autofill.", "usage"))),
        spec("app_info", "ⓘ", "Inspect an app", "Read metadata for one visible installed package. No private app data or complete system process list is available.", listOf(Param("package", "Android package name.", "com.android.chrome"))),
        spec("app_settings", "⚙", "App settings", "Open the selected app's system details screen; the user can manage its permissions, storage and force-stop.", listOf(Param("package", "Android package name.", "com.android.chrome"))),
        spec("rotation", "↻", "System rotation", "Set automatic rotation or a fixed default-display orientation and read the values back. Human orientations require agreeing display readouts; natural-axis quarter turns are exact setting values. Needs Modify system settings.", listOf(Param("state", "auto or locked.", "locked"), Param("orientation", "portrait, landscape, reverse_portrait, reverse_landscape, or precise natural-axis values: natural, quarter_turn, half_turn, three_quarter_turn. Only with locked.", "portrait")), Abilities.WRITE_SYSTEM_SETTINGS),
        spec("screen_timeout", "◷", "Screen timeout", "Set the system screen-off timeout, then read it back. Device policy may impose a tighter effective limit.", listOf(Param("seconds", "15–1800 seconds.", "120")), Abilities.WRITE_SYSTEM_SETTINGS)
    )
    private fun spec(id: String, glyph: String, label: String, help: String, params: List<Param> = emptyList(), ability: String? = null) =
        VerbSpec(id, glyph, label, help, params, ability, "Explains missing access or unsupported hardware; core typing stays available.", Verbs.GROUP_MEDIA)
}

/** A typed, closed runtime binding. New catalogue entries need an audited adapter. */
sealed interface PhoneRequest {
    data class Tools(val tab: String) : PhoneRequest
    data class Info(val section: String) : PhoneRequest
    data object Sensors : PhoneRequest
    data class Monitor(val sensor: String, val hz: Int, val seconds: Int) : PhoneRequest
    data class Access(val target: String) : PhoneRequest
    data class AppInfo(val packageName: String) : PhoneRequest
    data class AppSettings(val packageName: String) : PhoneRequest
    data class Rotation(val automatic: Boolean, val orientation: Int?, val relativeToNatural: Boolean = false) : PhoneRequest
    data class Timeout(val seconds: Int) : PhoneRequest

    companion object {
        val tabs = setOf("overview", "sensors", "apps", "access", "actions")
        val sections = setOf("device", "memory", "processes", "apps", "usage", "access")
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        fun packageName(raw: String?): String {
            require(raw != null && raw.length <= 255 && packagePattern.matches(raw)) { "Use an installed app's package name." }
            return raw
        }
        fun parse(command: Command): PhoneRequest {
            val spec = PhoneCatalogue.verbs.firstOrNull { it.id == command.verb } ?: error("No phone binding for this action.")
            require(command.named.keys.all { key -> spec.params.any { it.name == key } } && command.positional.size <= spec.params.size) { "Unexpected action argument." }
            fun pick(key: String, fallback: String? = null) = command.arg(key) ?: fallback ?: error("Missing $key.")
            fun choice(key: String, values: Set<String>, fallback: String? = null): String = pick(key, fallback).also { require(it in values) { "Invalid $key." } }
            fun integer(key: String, range: IntRange, fallback: String? = null) = pick(key, fallback).toIntOrNull().also { require(it != null && it in range) { "$key must be ${range.first}–${range.last}." } }!!
            return when (command.verb) {
                "phone_tools" -> Tools(choice("tab", tabs, "overview"))
                "phone_info" -> Info(choice("section", sections, "device"))
                "sensors" -> Sensors
                "sensor_monitor" -> Monitor(pick("sensor").also { require(Regex("s[0-9]+\\.-?[0-9]+\\.[0-9]+").matches(it) && it.length <= 64) { "Use a current sensor inventory key." } }, integer("hz", 1..20, "10"), integer("seconds", 1..300, "30"))
                "special_access" -> Access(choice("target", PhoneCatalogue.accessIds))
                "app_info" -> AppInfo(packageName(pick("package")))
                "app_settings" -> AppSettings(packageName(pick("package")))
                "rotation" -> {
                    val automatic = choice("state", setOf("auto", "locked")) == "auto"
                    require(!automatic || command.arg("orientation") == null) { "Automatic rotation does not take an orientation." }
                    val orientations = mapOf("portrait" to 0, "landscape" to 1, "reverse_portrait" to 2, "reverse_landscape" to 3)
                    val natural = mapOf("natural" to 0, "quarter_turn" to 1, "half_turn" to 2, "three_quarter_turn" to 3)
                    if (automatic) Rotation(true, null) else {
                        val selected = choice("orientation", orientations.keys + natural.keys, "portrait")
                        Rotation(false, (orientations + natural).getValue(selected), selected in natural)
                    }
                }
                "screen_timeout" -> Timeout(integer("seconds", 15..1800))
                else -> error("Unimplemented phone binding.")
            }
        }
    }
}

enum class PhoneOutcome { OBSERVED, VERIFIED, OPENED, REQUESTED, NEEDS_ACCESS, UNAVAILABLE, FAILED }
/** The same invocation's arguments accompany the result; callers must not reuse old receipts. */
data class PhoneReceipt(val command: Command, val outcome: PhoneOutcome, val detail: String)
