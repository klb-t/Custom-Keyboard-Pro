package com.example.core.vault

import java.net.URI
import java.util.Locale
import java.util.UUID

/** The data contract deliberately has no CVV, PIN, recovery seed or payment action. */
enum class VaultKind { LOGIN, PAYMENT_CARD }

data class VaultOrigin(
    val packageName: String,
    val certificateSha256: Set<String>,
    val httpsOrigin: String? = null
) {
    fun matches(other: VaultOrigin): Boolean =
        packageName == other.packageName && certificateSha256.isNotEmpty() &&
            certificateSha256 == other.certificateSha256 && httpsOrigin == other.httpsOrigin

    companion object {
        /** Exact HTTPS origins only: no suffix/subdomain matching or inferred app↔site association. */
        fun https(value: String): String? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", true) && uri.userInfo == null)
            require(uri.port == -1 || uri.port == 443)
            require(uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
            require(host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")))
            require(!host.contains("..") && host.split('.').all { it.isNotEmpty() && !it.startsWith('-') && !it.endsWith('-') })
            "https://$host"
        }.getOrNull()
    }
}

data class VaultEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: VaultKind,
    val label: String,
    val username: String = "",
    val password: String = "",
    val cardholder: String = "",
    val cardNumber: String = "",
    val expiryMonth: String = "",
    val expiryYear: String = "",
    val website: String = "",
    val origin: VaultOrigin? = null
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 128) { "Invalid vault entry ID." }
        require(label.isNotBlank() && label.length <= 200) { "A label of at most 200 characters is required." }
        require(username.length <= 1000 && password.length <= 4096) { "Entry is too long." }
        require(website.isBlank() || VaultOrigin.https(website) != null) { "Use an exact HTTPS origin, for example https://example.com." }
        when (kind) {
            VaultKind.LOGIN -> {
                require(password.isNotEmpty()) { "A password is required." }
                require(cardNumber.isEmpty() && cardholder.isEmpty() && expiryMonth.isEmpty() && expiryYear.isEmpty())
            }
            VaultKind.PAYMENT_CARD -> {
                require(password.isEmpty() && username.isEmpty())
                require(cardholder.isNotBlank() && cardholder.length <= 200) { "A cardholder name is required." }
                require(cardNumber.matches(Regex("[0-9]{12,19}")) && luhn(cardNumber)) { "Card number is not valid." }
                require(expiryMonth.toIntOrNull() in 1..12 && expiryYear.matches(Regex("20[0-9]{2}"))) { "Use an expiry month 1–12 and a four-digit year." }
            }
        }
        origin?.let {
            require(it.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
            require(it.certificateSha256.isNotEmpty() && it.certificateSha256.all { hash -> hash.matches(Regex("[a-f0-9]{64}")) })
            require(it.httpsOrigin == website.takeIf(String::isNotBlank))
        }
    }

    val summary: String get() = when (kind) {
        VaultKind.LOGIN -> username
        VaultKind.PAYMENT_CARD -> "•••• ${cardNumber.takeLast(4)} · $expiryMonth/$expiryYear"
    }

    companion object {
        fun luhn(number: String): Boolean {
            if (number.isEmpty() || number.any { !it.isDigit() }) return false
            var sum = 0
            number.reversed().forEachIndexed { index, c ->
                var n = c.digitToInt()
                if (index % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
                sum += n
            }
            return sum % 10 == 0
        }
    }
}

/** Standard CSV import is explicit and remains unbound until the user chooses a target app. */
object VaultCsv {
    const val MAX_CHARS = 1_048_576
    const val MAX_ENTRIES = 1000

    fun parse(text: String): List<VaultEntry> {
        require(text.length <= MAX_CHARS) { "Import is limited to 1 MiB." }
        val rows = rows(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "The CSV is empty." }
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        fun index(vararg names: String) = names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } } ?: -1
        val name = index("name", "title")
        val user = index("username", "login_username")
        val password = index("password", "login_password")
        val url = index("url", "login_uri", "origin")
        require(password >= 0 && user >= 0) { "Expected username/password columns (Chrome, Firefox or Bitwarden CSV)." }
        val entries = rows.drop(1).filter { row -> row.any(String::isNotBlank) }
        require(entries.size <= MAX_ENTRIES) { "Import is limited to 1000 entries." }
        return entries.mapIndexed { rowIndex, row ->
            fun cell(i: Int) = row.getOrNull(i).orEmpty()
            val rawUrl = cell(url)
            // Paths in browser exports are reduced to origins; HTTP entries stay vault-only.
            val website = runCatching {
                val uri = URI(rawUrl)
                VaultOrigin.https(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString())
            }.getOrNull().orEmpty()
            val entry = VaultEntry(kind = VaultKind.LOGIN, label = cell(name).ifBlank { website.ifBlank { "Imported login ${rowIndex + 1}" } },
                username = cell(user), password = cell(password), website = website)
            require(entry.password.isNotEmpty()) { "Row ${rowIndex + 2} has no password; nothing was imported." }
            entry.validate()
            entry
        }
    }

    private fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closedQuote = false
        var i = 0
        fun finishCell() { row += cell.toString(); cell.setLength(0); closedQuote = false }
        fun finishRow() { finishCell(); rows += row; row = mutableListOf(); require(rows.size <= MAX_ENTRIES + 2) { "Too many CSV rows." } }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ }
                    else { quoted = false; closedQuote = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closedQuote) { "Malformed CSV quotes." }; quoted = true }
                ',' -> finishCell()
                '\n' -> finishRow()
                '\r' -> { finishRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> { require(!closedQuote) { "Malformed CSV after closing quote." }; cell.append(c) }
            }
            i++
        }
        require(!quoted) { "Unclosed CSV quote." }
        if (cell.isNotEmpty() || row.isNotEmpty() || closedQuote) finishRow()
        return rows
    }
}
