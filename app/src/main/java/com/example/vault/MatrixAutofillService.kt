package com.example.vault

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.example.core.vault.VaultOrigin
import com.example.core.vault.VaultKind
import com.example.core.vault.VaultTargets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The service sees field metadata only. Secrets are opened solely in the authenticated activity. */
@RequiresApi(26)
class MatrixAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) return
        val response = runCatching {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return@runCatching null
            val parsed = VaultFormParser.parse(structure) ?: return@runCatching null
            if (parsed.packageName == packageName) return@runCatching null
            val origin = VaultTargets.origin(this, parsed.packageName, parsed.httpsOrigin)
            val fill = VaultFillRequest(origin, parsed.kind, parsed.fields, SystemClock.elapsedRealtime() + 120_000)
            val token = VaultFillRequests.add(fill)
            cancellationSignal.setOnCancelListener { fill.cancelled.set(true); VaultFillRequests.remove(token) }
            // All IDs/origin data stay in our process. Ignore framework fill-in extras entirely.
            val auth = Intent(this, VaultActivity::class.java).setAction("vault.fill.$token")
                .putExtra(VaultActivity.EXTRA_FILL_TOKEN, token)
            val pending = PendingIntent.getActivity(this, 0, auth, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Unlock IO Matrix vault")
            }
            @Suppress("DEPRECATION")
            FillResponse.Builder().setAuthentication(parsed.fields.values.toTypedArray(), pending.intentSender, presentation).build()
        }.getOrNull()
        if (!cancellationSignal.isCanceled) callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // No SaveInfo is advertised; form values are never collected in the background.
        callback.onFailure("Add or import an entry in the IO Matrix vault.")
    }
}

enum class VaultField(val kind: VaultKind) {
    USERNAME(VaultKind.LOGIN), PASSWORD(VaultKind.LOGIN),
    CARD_NUMBER(VaultKind.PAYMENT_CARD), CARDHOLDER(VaultKind.PAYMENT_CARD),
    EXPIRY_MONTH(VaultKind.PAYMENT_CARD), EXPIRY_YEAR(VaultKind.PAYMENT_CARD)
}

@RequiresApi(26)
data class VaultFillRequest(val origin: VaultOrigin, val kind: VaultKind, val fields: Map<VaultField, AutofillId>, val expiresAt: Long,
    val cancelled: AtomicBoolean = AtomicBoolean(false))

@RequiresApi(26)
object VaultFillRequests {
    private val requests = LinkedHashMap<String, VaultFillRequest>()
    @Synchronized fun add(request: VaultFillRequest): String {
        requests.entries.removeAll { it.value.expiresAt <= SystemClock.elapsedRealtime() }
        while (requests.size >= 20) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = request }
    }
    @Synchronized fun take(token: String): VaultFillRequest? = requests.remove(token)?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }
    @Synchronized fun remove(token: String) { requests.remove(token) }
}

@RequiresApi(26)
internal object VaultFormParser {
    data class Form(val packageName: String, val httpsOrigin: String?, val kind: VaultKind, val fields: Map<VaultField, AutofillId>)
    private data class Candidate(val field: VaultField, val id: AutofillId, val origin: String?)

    /** Conservative hints only. Mixed origins, duplicate roles and unverified HTTP are rejected. */
    fun parse(structure: AssistStructure): Form? {
        val packageName = structure.activityComponent?.packageName ?: return null
        val candidates = mutableListOf<Candidate>()
        val webOrigins = mutableSetOf<String>()
        var rejected = false
        var count = 0
        fun visit(node: AssistStructure.ViewNode, inheritedOrigin: String?, depth: Int) {
            if (rejected) return
            if (++count > 2048 || depth > 64) { rejected = true; return }
            var origin = inheritedOrigin
            node.webDomain?.let { domain ->
                if (Build.VERSION.SDK_INT < 28 || node.webScheme != "https") { rejected = true; return }
                origin = VaultOrigin.https("https://$domain")
                if (origin == null) { rejected = true; return }
                webOrigins += origin!!
            }
            val hints = node.autofillHints.orEmpty().toList() + node.htmlInfo?.attributes.orEmpty()
                .filter { it.first.equals("autocomplete", true) }.flatMap { it.second.split(' ') }
            val normalized = hints.map { it.lowercase(Locale.ROOT) }
            // Never fill sign-up/change-password fields with a stored password.
            if (normalized.any { it == "new-password" || it == "newpassword" }) { rejected = true; return }
            val roles = normalized.mapNotNull {
                when (it) {
                    "username", "emailaddress", "email" -> VaultField.USERNAME
                    "password", "current-password" -> VaultField.PASSWORD
                    "creditcardnumber", "cc-number" -> VaultField.CARD_NUMBER
                    "creditcardname", "cc-name" -> VaultField.CARDHOLDER
                    "creditcardexpirationmonth", "cc-exp-month" -> VaultField.EXPIRY_MONTH
                    "creditcardexpirationyear", "cc-exp-year" -> VaultField.EXPIRY_YEAR
                    else -> null
                }
            }.distinct()
            if (roles.size > 1) { rejected = true; return }
            if (node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val role = roles.singleOrNull()
                val id = node.autofillId
                if (role != null && id != null) candidates += Candidate(role, id, origin)
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i), origin, depth + 1)
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode, null, 0)
        if (rejected || webOrigins.size > 1 || candidates.isEmpty()) return null
        if (candidates.map { it.origin }.distinct().size != 1) return null
        val origin = candidates.first().origin
        if (webOrigins.isNotEmpty() && origin == null) return null
        if (candidates.groupBy { it.field }.any { it.value.size > 1 }) return null
        val kind = candidates.map { it.field.kind }.distinct().singleOrNull() ?: return null
        if (candidates.none { it.field == VaultField.PASSWORD || it.field == VaultField.CARD_NUMBER }) return null
        return Form(packageName, origin, kind, candidates.associate { it.field to it.id })
    }
}
