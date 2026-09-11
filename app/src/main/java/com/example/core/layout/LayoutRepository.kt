package com.example.core.layout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Built-in layouts plus whatever the user has made, under one lookup.
 *
 * User layouts live as JSON files in `filesDir/layouts` and any images they reference
 * in `filesDir/layout_assets`, so a layout is a pair of ordinary files the user can
 * copy off the device. A user layout whose id matches a built-in shadows it, which is
 * how "start from the stock QWERTY and change one key" works without a fork.
 */
object LayoutRepository {

    private const val LAYOUT_DIR = "layouts"
    private const val ASSET_DIR = "layout_assets"

    private val _layouts = MutableStateFlow<List<LayoutDef>>(BuiltinLayouts.ALL)
    val layouts: StateFlow<List<LayoutDef>> = _layouts.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    fun layoutDir(context: Context): File =
        File(context.filesDir, LAYOUT_DIR).also { if (!it.exists()) it.mkdirs() }

    fun assetDir(context: Context): File =
        File(context.filesDir, ASSET_DIR).also { if (!it.exists()) it.mkdirs() }

    fun reload() {
        val ctx = appContext ?: return
        val user = mutableListOf<LayoutDef>()
        layoutDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    user += LayoutJson.parse(file.readText())
                } catch (e: Exception) {
                    // One broken file must not hide the rest.
                }
            }
        val userIds = user.map { it.id }.toSet()
        _layouts.value = user + BuiltinLayouts.ALL.filter { it.id !in userIds }
    }

    fun all(): List<LayoutDef> = _layouts.value

    fun byId(id: String): LayoutDef? = _layouts.value.firstOrNull { it.id == id }

    /** Never fails: falls back through the enabled list to the stock Polish QWERTY. */
    fun resolve(id: String?, enabled: List<String>): LayoutDef =
        byId(id)
            ?: enabled.firstNotNullOfOrNull { byId(it) }
            ?: byId(BuiltinLayouts.QWERTY_PL.id)
            ?: BuiltinLayouts.QWERTY_PL

    fun save(layout: LayoutDef): Result<Unit> = runCatching {
        val ctx = appContext ?: error("Layout repository is not initialised.")
        File(layoutDir(ctx), "${sanitise(layout.id)}.json")
            .writeText(LayoutJson.writeString(layout))
        reload()
    }

    fun delete(id: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("Layout repository is not initialised.")
        File(layoutDir(ctx), "${sanitise(id)}.json").delete()
        reload()
    }

    /** True when this id has a user file, meaning "delete" restores a built-in. */
    fun isUserOwned(id: String): Boolean {
        val ctx = appContext ?: return false
        return File(layoutDir(ctx), "${sanitise(id)}.json").exists()
    }

    // --- bitmap assets -----------------------------------------------------

    fun saveAsset(name: String, bitmap: Bitmap): Result<String> = runCatching {
        val ctx = appContext ?: error("Layout repository is not initialised.")
        val safe = sanitise(name).ifEmpty { "asset_${System.currentTimeMillis()}" } + ".png"
        File(assetDir(ctx), safe).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        safe
    }

    fun loadAsset(name: String?): Bitmap? {
        if (name.isNullOrBlank()) return null
        val ctx = appContext ?: return null
        val file = File(assetDir(ctx), name)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun assetFile(name: String): File? {
        val ctx = appContext ?: return null
        return File(assetDir(ctx), name).takeIf { it.exists() }
    }

    fun listAssets(): List<String> {
        val ctx = appContext ?: return emptyList()
        return assetDir(ctx).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
    }

    private fun sanitise(raw: String): String =
        raw.trim().lowercase().map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").take(64)
}
