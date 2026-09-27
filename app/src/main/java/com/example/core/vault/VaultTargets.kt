package com.example.core.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object VaultTargets {
    data class App(val packageName: String, val label: String)

    fun installedApps(context: Context): List<App> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map { App(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }.filter { it.packageName != context.packageName }.sortedBy { it.label.lowercase() }

    /** Pin current signer(s), never merely a human-readable app name or domain suffix. */
    @Suppress("DEPRECATION")
    fun origin(context: Context, packageName: String, website: String? = null): VaultOrigin {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) { "Enter the installed target app's package name." }
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val hashes = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
        require(hashes.isNotEmpty()) { "Cannot verify this app's signing certificate." }
        require(website == null || VaultOrigin.https(website) == website)
        return VaultOrigin(packageName, hashes, website)
    }
}
