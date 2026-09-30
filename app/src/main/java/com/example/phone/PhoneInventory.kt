package com.example.phone

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.AlarmManager
import android.app.role.RoleManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.view.autofill.AutofillManager
import com.example.core.phone.PhoneCatalogue
import com.example.core.phone.SensorProfiles
import com.example.io.IoAccessibilityService

data class SensorEntry(val key: String, val sensor: Sensor, val samplePolicy: String, val permission: String? = null) {
    val maySample: Boolean get() = samplePolicy == "continuous" || samplePolicy == "on_change" || samplePolicy == "one_shot"
    fun describe(): String = buildString {
        append(key).append(" · ").append(sensor.name).append('\n')
        append("Type ").append(sensor.type).append(" · ").append(sensor.stringType).append("\nVendor ").append(sensor.vendor).append(" · version ").append(sensor.version)
        append("\nMaximum range ").append(sensor.maximumRange).append(" · resolution ").append(sensor.resolution).append(" · power ").append(sensor.power).append(" mA")
        append("\nMinimum delay ").append(sensor.minDelay).append(" μs · maximum delay ").append(sensor.maxDelay).append(" μs · wake-up ").append(sensor.isWakeUpSensor).append(" · dynamic ").append(sensor.isDynamicSensor)
        append("\nMonitoring: ").append(samplePolicy)
        permission?.let { append(" · needs ").append(it) }
    }
}

data class AccessState(val id: String, val status: String, val detail: String)
data class LaunchableApp(val packageName: String, val label: String)

/** Read-only snapshots, never an assertion that a permission or whole-phone view exists. */
object PhoneInventory {
    fun sensors(context: Context): List<SensorEntry> {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return (manager.getSensorList(Sensor.TYPE_ALL) + manager.getDynamicSensorList(Sensor.TYPE_ALL)).distinct()
            .sortedWith(compareBy<Sensor> { it.type }.thenBy { it.id }.thenBy { it.name }.thenBy { it.vendor })
            .mapIndexed { index, sensor ->
                val key = "s${sensor.type}.${sensor.id}.$index"
                // Inventory includes all vendor/physiological sensors; sampling needs an audited
                // permission profile. Health integration also needs a privacy/rationale activity.
                val readable = SensorProfiles.audited(sensor.type)
                val policy = when {
                    !readable -> "inventory only: no audited permission/format adapter"
                    sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
                    sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
                    sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
                    else -> "inventory only: special-trigger reporting mode"
                }
                val permission = if (sensor.type in setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR) && Build.VERSION.SDK_INT >= 29) "android.permission.ACTIVITY_RECOGNITION" else null
                SensorEntry(key, sensor, policy, permission)
            }
    }

    fun usageGranted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        return runCatching { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED }.getOrDefault(false)
    }

    fun access(context: Context): List<AccessState> = PhoneCatalogue.routes.filter { it.id in PhoneCatalogue.accessIds }.map { route ->
        if (Build.VERSION.SDK_INT < route.minimumApi) return@map AccessState(route.id, "unavailable", "Requires Android API ${route.minimumApi}.")
        val granted: Boolean? = runCatching {
            when (route.id) {
                "usage" -> usageGranted(context)
                "overlay" -> Settings.canDrawOverlays(context)
                "write_settings" -> Settings.System.canWrite(context)
                "accessibility" -> IoAccessibilityService.isEnabled(context)
                "battery" -> (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                "exact_alarm" -> if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() ?: false else false
                "all_files" -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else false
                "assistant" -> if (Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.ROLE_SERVICE) as? RoleManager)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false else null
                "autofill" -> if (Build.VERSION.SDK_INT >= 26) context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() ?: false else false
                else -> null
            }
        }.getOrNull()
        val status = when {
            route.id in setOf("notifications", "exact_alarm", "all_files") -> "adapter absent" + if (granted == true) " · system grant present" else ""
            granted == true -> "granted"
            granted == false -> "not granted"
            else -> "unknown"
        }
        AccessState(route.id, status, route.explanation)
    }

    fun apps(context: Context): List<LaunchableApp> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, 0).map { info ->
            LaunchableApp(info.activityInfo.packageName, info.loadLabel(context.packageManager).toString().take(200))
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun app(context: Context, packageName: String): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val application = info.applicationInfo ?: error("Package metadata is unavailable.")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else { @Suppress("DEPRECATION") info.versionCode.toLong() }
        return "${application.loadLabel(context.packageManager)}\n$packageName\nVersion ${info.versionName ?: "unknown"} ($versionCode)\nTarget API ${application.targetSdkVersion} · UID ${application.uid}\nEnabled ${application.enabled}\nFirst installed ${java.util.Date(info.firstInstallTime)}\nLast updated ${java.util.Date(info.lastUpdateTime)}\n\nOnly visible package metadata. Private app data and its complete process state are not accessible."
    }

    fun snapshot(context: Context, section: String): String = when (section) {
        "device" -> buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nABI ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("Developer options: ").append(flag(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)).append('\n')
            append("USB debugging setting: ").append(flag(context, Settings.Global.ADB_ENABLED)).append('\n')
            append("These are read-only flags, not proof of a connected authorized ADB bridge.\n")
            append("${sensors(context).size} reported sensors. Package visibility, permissions and hardware vary by device.")
        }
        "memory" -> {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: error("Activity manager unavailable.")
            val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
            val runtime = Runtime.getRuntime()
            val disk = StatFs(context.filesDir.absolutePath)
            "System available RAM ${mib(memory.availMem)} MiB / ${mib(memory.totalMem)} MiB\nLow memory ${memory.lowMemory} · threshold ${mib(memory.threshold)} MiB\nOwn Java heap used ${mib(runtime.totalMemory() - runtime.freeMemory())} MiB · max ${mib(runtime.maxMemory())} MiB\nApp storage available ${mib(disk.availableBytes)} MiB\nSnapshots fluctuate; Java heap does not include all native/GPU allocations."
        }
        "processes" -> {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: error("Activity manager unavailable.")
            val all = manager.runningAppProcesses.orEmpty()
            "Android returned ${all.size} visible processes; this is not all phone processes. Other apps' full state, arbitrary kill/force-stop and secure developer flags need a separately authorized privileged bridge.\n\n" +
                all.take(100).joinToString("\n") { "${it.processName.take(200)} · PID ${it.pid} · UID ${it.uid} · importance ${it.importance}${if (it.uid == Process.myUid()) " · own UID" else " · limited visible metadata"}" }
        }
        "apps" -> "Launchable apps visible through the declared launcher query:\n" + apps(context).take(200).joinToString("\n") { "${it.label} · ${it.packageName}" }
        "usage" -> {
            require(usageGranted(context)) { "App usage access has not been granted. Open Special access → App usage access, then refresh." }
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: error("Usage service unavailable.")
            val end = System.currentTimeMillis()
            "Locally observed app usage during the last 24 hours. It is historical aggregate usage, not live running processes; the device may return no history while locked.\n\n" +
                manager.queryAndAggregateUsageStats(end - 86_400_000L, end).values.sortedByDescending { it.lastTimeUsed }.take(100).joinToString("\n") { "${it.packageName} · foreground ${it.totalTimeInForeground / 1000}s · last ${java.util.Date(it.lastTimeUsed)}" }
        }
        "access" -> access(context).joinToString("\n\n") { "${it.id}: ${it.status}\n${it.detail}" }
        else -> error("Unknown diagnostic section.")
    }

    private fun flag(context: Context, key: String): String = runCatching {
        when (Settings.Global.getInt(context.contentResolver, key, -1)) { 1 -> "enabled"; 0 -> "disabled"; else -> "unknown" }
    }.getOrDefault("unreadable")
    private fun mib(bytes: Long) = bytes / (1024 * 1024)
}
