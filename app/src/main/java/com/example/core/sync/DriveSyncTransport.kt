package com.example.core.sync

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Google owns storage and account grants. IO Matrix has no sync server or refresh token. */
class DriveSyncTransport(private val token: String) {
    data class RemoteFile(val id: String, val name: String, val size: Long)
    data class Account(val email: String, val id: String)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun account(): Account {
        val json = JSONObject(String(get("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,permissionId)", 16_384)))
        val user = json.getJSONObject("user")
        return Account(user.getString("emailAddress"), user.getString("permissionId"))
    }
    fun list(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder").addQueryParameter("q", "trashed = false and name contains 'io-matrix-sync-v1-'")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size)").addQueryParameter("pageSize", "100")
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
            val json = JSONObject(String(get(url.toString(), 128 * 1024)))
            val rows = json.getJSONArray("files")
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                val name = row.getString("name")
                if (name.matches(Regex("io-matrix-sync-v1-[A-Za-z0-9_-]{16,80}\\.jwe"))) {
                    val file = RemoteFile(row.getString("id"), name, row.optString("size", "0").toLong())
                    require(file.size in 1..SyncJson.MAX_BYTES.toLong()) { "An encrypted device snapshot exceeds the safe limit." }
                    files += file
                }
            }
            require(files.size <= 32) { "More than 32 device snapshots; sync stopped without deleting any files." }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        require(files.sumOf { it.size } <= 64L * 1024 * 1024) { "Device snapshots exceed this run's 64 MiB download limit." }
        return files
    }
    fun download(file: RemoteFile): ByteArray = get("https://www.googleapis.com/drive/v3/files/${safeId(file.id)}?alt=media", SyncJson.MAX_BYTES)
    fun upload(device: String, bytes: ByteArray, files: List<RemoteFile>) {
        require(device.matches(Regex("[A-Za-z0-9_-]{16,80}")) && bytes.size <= SyncJson.MAX_BYTES)
        val name = "io-matrix-sync-v1-$device.jwe"
        val existing = files.firstOrNull { it.name == name }
        val type = "application/jose".toMediaType()
        val request = if (existing != null) Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/${safeId(existing.id)}?uploadType=media")
            .patch(bytes.toRequestBody(type))
        else {
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray(listOf("appDataFolder"))).put("mimeType", "application/jose")
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(bytes.toRequestBody(type)).build()
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id").post(body)
        }
        execute(request, 16_384)
    }
    private fun safeId(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,180}"))) }
    private fun get(url: String, limit: Int): ByteArray = execute(Request.Builder().url(url), limit)
    private fun execute(builder: Request.Builder, limit: Int): ByteArray = client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
        check(response.isSuccessful) { when (response.code) {
            401 -> "Google access expired or was revoked. Connect again."
            403 -> "Google denied Drive app-data access. Check Drive API setup, consent and account restrictions."
            429 -> "Google requested a pause. Retry sync later."
            else -> "Drive request failed (HTTP ${response.code}); previous device files were preserved."
        } }
        val body = response.body ?: error("Google returned no data")
        require(body.contentLength() <= limit || body.contentLength() < 0)
        body.byteStream().use { SyncRepository.readBounded(it, limit) }
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    companion object { const val SCOPE = "https://www.googleapis.com/auth/drive.appdata" }
}
