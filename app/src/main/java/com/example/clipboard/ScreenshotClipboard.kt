package com.example.clipboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.RequiresApi
import com.example.core.clipboard.ClipStore
import com.example.core.config.SettingsStore
import com.example.core.data.ClipboardEntity
import com.example.core.data.KeyboardRepository
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** An explicit Screenshot command. Secure windows and platform refusals stay refused. */
object ScreenshotClipboard {
    private val serial = AtomicLong()
    private val pending = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @RequiresApi(30)
    fun capture(service: AccessibilityService, notice: (String) -> Unit) {
        if (com.example.ime.liveKeyboard?.let { it.isInputViewShown && it.editor.isSensitive } == true) {
            notice("Screenshots are not saved while a private input is active."); return
        }
        val settings = SettingsStore.current
        if (!settings.clipboardEnabled || !settings.clipboardKeepFiles) {
            notice("Enable clipboard history and file copies to keep screenshots."); return
        }
        val id = serial.incrementAndGet()
        if (!pending.compareAndSet(0, id)) { notice("A screenshot is already being captured."); return }
        main.postDelayed({ if (pending.compareAndSet(id, 0)) notice("Screenshot timed out. Try again or share a system screenshot to IO Matrix.") }, 8000)
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) {
                if (pending.compareAndSet(id, 0)) notice("Android refused the screenshot (code $errorCode). Protected windows cannot be captured; try the system screenshot/share action.")
            }
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                if (!pending.compareAndSet(id, 0)) { buffer.close(); return }
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            buffer.use {
                                val hardware = Bitmap.wrapHardwareBuffer(it, result.colorSpace) ?: error("Cannot read captured image")
                                try {
                                    require(hardware.width.toLong() * hardware.height <= 32_000_000L)
                                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("Cannot copy screenshot")
                                    try { save(service, bitmap, settings.clipboardMaxFileMb.coerceAtLeast(0) * 1_000_000L, settings.clipboardMaxItems) }
                                    finally { bitmap.recycle() }
                                } finally { hardware.recycle() }
                            }
                        }
                        notice("Screenshot saved as an image in clipboard history.")
                    } catch (e: CancellationException) { buffer.close(); throw e }
                    catch (_: Exception) { buffer.close(); notice("Could not keep screenshot bytes. Check the clipboard file-size limit and available storage.") }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val root = service.rootInActiveWindow
                if (root != null) {
                    try { service.takeScreenshotOfWindow(root.windowId, service.mainExecutor, callback) }
                    finally { @Suppress("DEPRECATION") root.recycle() }
                } else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            } else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (_: Exception) { if (pending.compareAndSet(id, 0)) notice("Screenshot access is unavailable. Share an image to IO Matrix instead.") }
    }

    private suspend fun save(service: AccessibilityService, bitmap: Bitmap, limit: Long, maxItems: Int) {
        val file = File(ClipStore.dir(service), "${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { output ->
                var bytes = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) { checkLimit(1); output.write(value) }
                    override fun write(data: ByteArray, offset: Int, count: Int) { checkLimit(count); output.write(data, offset, count) }
                    fun checkLimit(count: Int) { bytes += count; require(limit <= 0 || bytes <= limit) { "Screenshot exceeds file limit" } }
                }
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded))
            }
            KeyboardRepository(service).rememberClipEntry(ClipboardEntity(type = ClipboardEntity.TYPE_FILE,
                content = "Screenshot", label = "Screenshot", mime = "image/png", filePath = file.absolutePath,
                sizeBytes = file.length()), maxItems)
        } catch (e: Exception) { file.delete(); throw e }
    }
}
