package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.AsrEngines
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.AiCapability
import com.example.core.discovery.Privacy
import com.example.core.discovery.ProviderCatalog
import com.example.core.setup.ProbeResult
import com.example.core.setup.ProviderProbe
import com.example.core.setup.Recommendation
import com.example.core.setup.SetupAdvisor
import com.example.core.setup.SetupWants
import kotlinx.coroutines.launch

/**
 * The screen that answers "I have no account anywhere, now what".
 *
 * Everything it knows comes from the catalogue, so it can be honest about providers
 * that did not exist when it was written. What it does *not* do is ask a model which
 * to recommend — it is advising somebody who has not configured one, which is the
 * whole reason it exists.
 *
 * It is also skippable at every point, and says so. A wizard that has to be completed
 * before the keyboard works would contradict the one rule this app has kept
 * throughout: the defaults are complete, and nothing is required.
 */
@Composable
fun SetupWizardScreen(settings: Settings, onDone: () -> Unit, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    var wants by remember { mutableStateOf(SetupWants()) }
    var notes by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var probing by remember { mutableStateOf(true) }

    // Asked unprompted, because somebody running a model on their own phone should be
    // offered it rather than having to know it is an option.
    LaunchedEffect(Unit) {
        found = runCatching { ProviderProbe.findLocal(settings) }.getOrDefault(emptyList())
        probing = false
    }

    val providers = remember(settings.customProvidersJson, settings.fetchedProvidersJson) {
        ProviderCatalog.all(settings)
    }
    val effectiveWants = remember(wants, notes) { wants.copy(notes = notes) }
    val advice = remember(providers, effectiveWants) {
        SetupAdvisor.recommend(providers, effectiveWants)
    }
    val compromise = remember(providers, effectiveWants) {
        if (advice.isEmpty()) SetupAdvisor.fallback(providers, effectiveWants) else null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Setting up",
            subtitle = "The keyboard already works — typing, layouts, the symbol boards " +
                "and dictation on this phone need nothing at all. This is only about the " +
                "parts that talk to a model, and you can leave at any point."
        ) {
            ActionRow(
                "Skip all this",
                "Everything here can be set later, and most of it never has to be",
                onClick = {
                    SettingsStore.update { it.copy(setupDone = true) }
                    onDone()
                }
            )
        }

        if (probing) {
            SettingsSection("Looking around") { InfoRow("Checking whether anything is running here…") }
        } else if (found.isNotEmpty()) {
            SettingsSection(
                title = "Already running on this device or network",
                subtitle = "Found without being asked. Nothing sent anywhere, no account, no key."
            ) {
                found.forEach { result ->
                    val spec = providers.firstOrNull { it.id == result.provider }
                    ActionRow(
                        spec?.label ?: result.provider,
                        if (result.models.isEmpty()) "Answering"
                        else "Answering · ${result.models.size} model" +
                            (if (result.models.size == 1) "" else "s") +
                            " · ${result.models.take(3).joinToString(", ")}",
                        trailing = "Use",
                        onClick = {
                            SettingsStore.update {
                                it.copy(
                                    aiEnabled = true,
                                    aiProvider = result.provider,
                                    aiModel = result.models.firstOrNull() ?: spec?.defaultModel.orEmpty(),
                                    setupDone = true
                                )
                            }
                            onDone()
                        }
                    )
                    Divider()
                }
            }
        }

        SettingsSection(
            title = "What matters to you",
            subtitle = "These are rules, not preferences — anything you switch on here " +
                "removes providers from the list rather than moving them down it."
        ) {
            SwitchRow(
                label = "Nothing may leave this device",
                description = "Leaves only what runs here. Fewer options, and no way for " +
                    "any of this to reach anyone.",
                checked = wants.mustStayOnDevice,
                onChange = { wants = wants.copy(mustStayOnDevice = it) }
            )
            Divider()
            SwitchRow(
                label = "No payment details",
                description = "Rules out anything that wants a card before it will answer.",
                checked = wants.noCard,
                onChange = { wants = wants.copy(noCard = it) }
            )
            Divider()
            SwitchRow(
                label = "Free only",
                description = "Only what has a free tier you can actually use.",
                checked = wants.freeOnly,
                onChange = { wants = wants.copy(freeOnly = it) }
            )
            Divider()
            SwitchRow(
                label = "Do not train on what I send",
                description = "Drops anything that trains on your text by default, and " +
                    "anything whose policy is not established.",
                checked = wants.avoidTraining,
                onChange = { wants = wants.copy(avoidTraining = it) }
            )
            Divider()
            SwitchRow(
                label = "One account rather than several",
                description = "Prefers a gateway that reaches many models with one key.",
                checked = wants.preferOneAccount,
                onChange = { wants = wants.copy(preferOneAccount = it) }
            )
        }

        SettingsSection(
            title = "What you want it to do",
            subtitle = "Only providers that do all of these are offered — two accounts " +
                "covering half each is where people give up."
        ) {
            listOf(
                AiCapability.CHAT to "Writing and rewriting",
                AiCapability.COMPLETE to "Finishing sentences as you type",
                AiCapability.TRANSCRIBE to "Dictation",
                AiCapability.OCR to "Text out of a picture",
                AiCapability.IMAGE to "Making pictures"
            ).forEach { (capability, label) ->
                SwitchRow(
                    label = label,
                    checked = capability in wants.capabilities,
                    onChange = { on ->
                        wants = wants.copy(
                            capabilities = if (on) wants.capabilities + capability
                            else wants.capabilities - capability
                        )
                    }
                )
            }
            TextRow(
                label = "Anything else, in your own words",
                description = "Matched against what each provider is known to be good at.",
                value = notes,
                placeholder = "I dictate a lot, in Polish, and I want it fast",
                onChange = { notes = it }
            )
        }

        val shown = advice.ifEmpty { compromise?.first.orEmpty() }
        SettingsSection(
            title = if (advice.isNotEmpty()) "What fits" else "Nothing fits exactly",
            subtitle = when {
                advice.isNotEmpty() ->
                    "Ranked by what you said. Every reason is spelled out, so you can " +
                        "disagree with one rather than having to trust a number."
                compromise != null ->
                    "Nothing matches all of that. Here is what you get ${compromise.second}."
                else -> "Nothing in the catalogue matches, even with your preferences dropped."
            }
        ) {
            if (shown.isEmpty()) {
                InfoRow("Try switching something off above.")
            } else {
                shown.forEach { recommendation ->
                    RecommendationRow(recommendation, onNavigate)
                    Divider()
                }
            }
        }

        SettingsSection(
            title = "Keeping this list current",
            subtitle = "The catalogue shipped with the app is a snapshot of the day it " +
                "was built. There is no address here by default, on purpose: one would " +
                "mean every install of this keyboard quietly contacting somewhere I chose."
        ) {
            TextRow(
                label = "Catalogue address",
                description = "An https address serving the same shape as the bundled list.",
                value = settings.catalogUrl,
                placeholder = "none",
                onChange = { url -> SettingsStore.update { it.copy(catalogUrl = url.trim()) } }
            )
            if (settings.catalogUrl.isNotBlank()) {
                ActionRow(
                    "Fetch now",
                    if (settings.catalogFetchedAt > 0L) "Last fetched once already"
                    else "Never fetched",
                    onClick = {
                        scope.launch { com.example.core.setup.CatalogUpdate.refresh() }
                    }
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    SettingsStore.update { it.copy(setupDone = true) }
                    onDone()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Done for now") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Nothing above is required. The keyboard types, switches layouts, opens " +
                    "its symbol boards and dictates on this phone whether or not any of " +
                    "this is ever filled in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun RecommendationRow(recommendation: Recommendation, onNavigate: (String) -> Unit) {
    val provider = recommendation.provider
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(provider.label, style = MaterialTheme.typography.titleSmall)
        if (provider.note.isNotBlank()) {
            Text(
                provider.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(6.dp))
        recommendation.reasons.forEach {
            Text("✓  $it", style = MaterialTheme.typography.bodySmall)
        }
        recommendation.caveats.forEach {
            Text(
                "!  $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            Privacy.label(provider.privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                SettingsStore.update { current ->
                    current.copy(
                        aiProvider = provider.id,
                        aiModel = provider.defaultModel,
                        asrProvider = if (provider.can(AiCapability.TRANSCRIBE)) provider.id
                        else current.asrProvider,
                        asrEngine = if (provider.can(AiCapability.TRANSCRIBE)) AsrEngines.PROVIDER
                        else current.asrEngine
                    )
                }
                // Not marked done: a key still has to be pasted, and pretending
                // otherwise leaves the user on a home screen that quietly does nothing.
                onNavigate(com.example.MainActivity.ROUTE_AI)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (provider.needsKey) "Choose this, then paste a key"
                else "Choose this"
            )
        }
    }
}
