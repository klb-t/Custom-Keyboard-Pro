package com.example.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import com.example.core.io.Command
import com.example.core.phone.*
import java.util.concurrent.ConcurrentHashMap

/** Bounded platform adapters. A launched setup page and a changed setting are distinct receipts. */
object PhoneRuntime {
    fun execute(context: Context, command: Command): PhoneReceipt = try {
        val request = PhoneRequest.parse(command)
        when (request) {
            is PhoneRequest.Tools -> launch(context, command, Intent(context, PhoneToolsActivity::class.java).putExtra("tab", request.tab), "Phone toolbox opened; no capture or grant was started.")
            is PhoneRequest.Monitor -> {
                val sensor = PhoneInventory.sensors(context).firstOrNull { it.key == request.sensor }
                if (sensor == null) receipt(command, PhoneOutcome.UNAVAILABLE, "The selected sensor is not in the current inventory. Refresh it.")
                else if (!sensor.maySample) receipt(command, PhoneOutcome.UNAVAILABLE, sensor.samplePolicy)
                else launch(context, command, Intent(context, PhoneToolsActivity::class.java).putExtra("tab", "sensors").putExtra("sensor", request.sensor).putExtra("hz", request.hz).putExtra("seconds", request.seconds), "Sensor review opened. Press Start there; this request did not capture samples.")
            }
            is PhoneRequest.Info -> if (request.section == "usage" && !PhoneInventory.usageGranted(context)) {
                receipt(command, PhoneOutcome.NEEDS_ACCESS, "App usage access is missing. Open Special access → App usage access; no history was read.")
            } else receipt(command, PhoneOutcome.OBSERVED, PhoneInventory.snapshot(context, request.section))
            PhoneRequest.Sensors -> receipt(command, PhoneOutcome.OBSERVED, PhoneInventory.sensors(context).joinToString("\n\n") { it.describe() }.ifEmpty { "SensorManager reported no sensors." })
            is PhoneRequest.Access -> openRoute(context, command, request.target)
            is PhoneRequest.AppInfo -> receipt(command, PhoneOutcome.OBSERVED, PhoneInventory.app(context, request.packageName))
            is PhoneRequest.AppSettings -> {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(request.packageName, 0)
                launch(context, command, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${request.packageName}")), "System details opened for ${request.packageName}; no permission or process state was changed by this request.")
            }
            is PhoneRequest.Rotation -> rotation(context, command, request)
            is PhoneRequest.Timeout -> timeout(context, command, request)
        }
    } catch (_: SecurityException) { receipt(command, PhoneOutcome.NEEDS_ACCESS, "Android refused this operation. Recheck the selected feature's access.") }
    catch (_: PackageManager.NameNotFoundException) { receipt(command, PhoneOutcome.UNAVAILABLE, "This package is not installed or is not visible to this app.") }
    catch (e: IllegalArgumentException) { receipt(command, PhoneOutcome.FAILED, e.message ?: "Invalid operation arguments.") }
    catch (_: Exception) { receipt(command, PhoneOutcome.FAILED, "The platform operation failed; the requested effect is not verified.") }

    fun openRoute(context: Context, command: Command, id: String): PhoneReceipt {
        // Preserve existing keyboard navigation names while access targets retain
        // their distinct grant pages. A notification page is not listener access.
        val key = if (command.verb == "system_settings") when (id.lowercase().trim()) {
            "notifications" -> "notification_settings"
            "battery" -> "battery_saver"
            else -> id
        } else id
        val route = PhoneCatalogue.route(key) ?: return receipt(command, PhoneOutcome.FAILED, "Unknown settings route. Choose a catalogue route.")
        if (Build.VERSION.SDK_INT < route.minimumApi) return receipt(command, PhoneOutcome.UNAVAILABLE, "${route.label} requires Android API ${route.minimumApi}.")
        val intent = if (route.id == "assistant" && Build.VERSION.SDK_INT >= 29) runCatching {
            val manager = context.getSystemService(RoleManager::class.java)
            if (manager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) else null
        }.getOrNull() ?: Intent(route.action) else Intent(route.action)
        if (route.packageScoped) intent.data = Uri.parse("package:${context.packageName}")
        return launch(context, command, intent, "${route.label} setup screen opened. ${route.explanation} The grant/effect has not been verified.")
    }

    private fun rotation(context: Context, command: Command, request: PhoneRequest.Rotation): PhoneReceipt {
        if (!Settings.System.canWrite(context)) return receipt(command, PhoneOutcome.NEEDS_ACCESS, "Rotation needs this app's Modify system settings access.")
        val resolver = context.contentResolver
        // USER_ROTATION controls the default display. Activity resources/rotation can
        // describe a locked/letterboxed/split-screen window, so they are not evidence.
        val target = request.orientation?.let { orientation ->
            if (request.relativeToNatural) orientation else {
                val manager = context.applicationContext.getSystemService(DisplayManager::class.java)
                val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return receipt(command, PhoneOutcome.UNAVAILABLE, "Default display information is unavailable. No rotation setting was written; choose a precise natural-axis quarter turn instead.")
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                val mode = display.mode
                val naturalLandscape = RotationMapping.naturalLandscape(size.x, size.y, display.rotation, mode.physicalWidth, mode.physicalHeight)
                    ?: return receipt(command, PhoneOutcome.UNAVAILABLE, "Default-display shape/rotation readouts are ambiguous or disagree. No rotation setting was written; choose natural, quarter_turn, half_turn or three_quarter_turn explicitly.")
                RotationMapping.humanToUserRotation(orientation, naturalLandscape)
            }
        }
        if (target != null && !Settings.System.putInt(resolver, Settings.System.USER_ROTATION, target)) return receipt(command, PhoneOutcome.FAILED, "Android refused the requested user rotation.")
        val automatic = if (request.automatic) 1 else 0
        if (!Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, automatic)) return receipt(command, PhoneOutcome.FAILED, "Android refused the rotation mode. A previously accepted orientation write is not rolled back.")
        val modeRead = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, -1)
        val positionRead = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, -1)
        val verified = modeRead == automatic && (target == null || positionRead == target)
        return receipt(command, if (verified) PhoneOutcome.VERIFIED else PhoneOutcome.REQUESTED, "Default-display rotation settings ${if (verified) "were read back" else "were requested but did not match readback"}: automatic=$modeRead, userRotation=$positionRead. ${if (target != null && !request.relativeToNatural) "The human orientation used a cross-checked display inference, not a platform guarantee. " else ""}App orientation locks and OEM policy can still determine the displayed orientation; no physical rotation effect is verified.")
    }

    private fun timeout(context: Context, command: Command, request: PhoneRequest.Timeout): PhoneReceipt {
        if (!Settings.System.canWrite(context)) return receipt(command, PhoneOutcome.NEEDS_ACCESS, "Screen timeout needs this app's Modify system settings access.")
        val milliseconds = request.seconds * 1000
        if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, milliseconds)) return receipt(command, PhoneOutcome.FAILED, "Android refused the timeout setting.")
        val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        return receipt(command, if (actual == milliseconds) PhoneOutcome.VERIFIED else PhoneOutcome.REQUESTED, "Timeout setting readback: ${actual / 1000}s. This verifies the configured value; administrator policy or an app keeping the screen awake may change the effective behavior.")
    }

    fun torch(context: Context, command: Command): PhoneReceipt {
        val state = command.arg("state") ?: "toggle"
        if (state !in setOf("on", "off", "toggle") || command.named.keys.any { it != "state" } || command.positional.size > 1) return receipt(command, PhoneOutcome.FAILED, "Torch state must be on, off or toggle.")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return receipt(command, PhoneOutcome.UNAVAILABLE, "This phone does not advertise flashlight hardware.")
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return receipt(command, PhoneOutcome.NEEDS_ACCESS, "The camera permission is required for the flashlight. Grant it from Phone tools → Actions; no camera images are captured.")
        return try {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return receipt(command, PhoneOutcome.UNAVAILABLE, "Camera service unavailable.")
            val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return receipt(command, PhoneOutcome.UNAVAILABLE, "This phone reports no camera flash.")
            TorchState.watch(camera)
            val wanted = when (state) { "on" -> true; "off" -> false; else -> {
                val previous = TorchState.states[id] ?: return receipt(command, PhoneOutcome.REQUESTED, "Waiting for Android's flashlight state. Retry toggle, or choose on/off explicitly.")
                !previous
            } }
            camera.setTorchMode(id, wanted)
            receipt(command, PhoneOutcome.REQUESTED, "Flashlight ${if (wanted) "on" else "off"} request accepted. Android's asynchronous torch callback, not this request, reports the actual state.")
        } catch (_: SecurityException) { receipt(command, PhoneOutcome.NEEDS_ACCESS, "Android refused flashlight access.") }
        catch (_: Exception) { receipt(command, PhoneOutcome.UNAVAILABLE, "Flashlight unavailable or busy; the requested effect is not verified.") }
    }

    private object TorchState {
        val states = ConcurrentHashMap<String, Boolean>()
        private var watching = false
        @Synchronized fun watch(camera: CameraManager) {
            if (watching) return
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { states[cameraId] = enabled }
                override fun onTorchModeUnavailable(cameraId: String) { states.remove(cameraId) }
            }, Handler(Looper.getMainLooper()))
            watching = true
        }
    }
    private fun launch(context: Context, command: Command, intent: Intent, explanation: String): PhoneReceipt = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        receipt(command, PhoneOutcome.OPENED, explanation)
    } catch (_: ActivityNotFoundException) { receipt(command, PhoneOutcome.UNAVAILABLE, "No system activity handles this route on this device; no effect was applied.") }
    catch (_: SecurityException) { receipt(command, PhoneOutcome.NEEDS_ACCESS, "Android refused opening this setup route.") }
    private fun receipt(command: Command, outcome: PhoneOutcome, text: String) = PhoneReceipt(command.copy(positional = command.positional.toList(), named = command.named.toMap()), outcome, text.take(24_000))
}
