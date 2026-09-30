package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.assistant.*
import com.example.core.caps.Abilities
import com.example.core.caps.Need
import com.example.core.config.SettingsStore
import com.example.core.io.Command
import com.example.core.io.Verbs
import com.example.phone.PhoneToolsActivity
import kotlinx.coroutines.*

/** Explicit, foreground-only proposal/review host. Incoming intents confer no authority. */
class GoalAssistantActivity : Activity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var planning: Job? = null
    private var executing: Job? = null
    private var generation = 0L
    private var foreground = false
    private var settingsReady = false
    private var voiceToken: String? = null
    private var voiceHidden = false
    private lateinit var goal: EditText
    private lateinit var status: TextView
    private lateinit var plansView: LinearLayout
    private var alternatives = emptyList<GoalPlan>()
    private var session: GoalSession? = null
    private var recognition: ForegroundSpeech? = null
    private fun now() = SystemClock.elapsedRealtime()
    private fun ready() = foreground && settingsReady && !voiceHidden &&
        !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    private fun snapshot() = GoalCatalogue.snapshot(this, ready())
    private fun line(value: String) = TextView(this).apply { text = value; setTextIsSelectable(true); textSize = 15f; setPadding(0, 10, 0, 8) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun tell(value: String) { status.text = value }
    private fun invalidate() {
        generation++; planning?.cancel(); planning = null
        executing?.cancel(); executing = null
        session?.stop(); session = null; alternatives = emptyList()
        if (::plansView.isInitialized) plansView.removeAllViews()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(line("IO Matrix · Goal assistant\nPlans are proposals. Nothing runs until a specific step is reviewed and confirmed. This host does not read other apps, clipboard or editor context."))
        goal = EditText(this).apply {
            hint = "Describe a goal, or enter do:volume_set level=0.3 stream=music"
            minLines = 2; maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
            // Do not restore a stale executing plan across process/activity recreation.
            isSaveEnabled = false
        }
        body.addView(goal)
        goal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Typing wins over a pending late transcript; never replace a new edit.
                recognition?.close(); recognition = null
                invalidate()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        body.addView(button("Speak on device") { speak() })
        body.addView(button("Review explicit do: command (offline)") { localCommand() })
        body.addView(button("Plan with configured model…") { modelPlan() })
        body.addView(button("Phone capabilities and setup") { capabilities() })
        body.addView(button("Choose system assistant") { assistantRole() })
        body.addView(button("Use this workspace independently of system session") {
            AssistantSessionVisibility.currentSession.detach(voiceToken)
            voiceToken = null; voiceHidden = false
            tell("Foreground workspace. Speech still requires Speak; planning and every effect require review.")
            render()
        })
        body.addView(button("Colour organ setup (microphone → Wi-Fi light)") {
            startActivity(Intent(this, ColourOrganActivity::class.java))
        })
        body.addView(button("Import plan JSON") { document(false) })
        body.addView(button("Save plan JSON") { document(true) })
        status = line("Loading local settings. No microphone or plan starts automatically.")
        body.addView(status)
        plansView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(plansView)
        // Stop is outside the scrollable plan and remains reachable for long proposals.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(button("Stop / invalidate pending approvals") {
            generation++; planning?.cancel(); recognition?.close(); recognition = null
            executing?.cancel(); executing = null
            session?.stop(); tell("Stopped. No further step or model result will execute. Already applied effects are not rolled back."); render()
        })
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val pad = (12 * resources.displayMetrics.density).toInt()
            view.setPadding(pad + b.left, b.top, pad + b.right, b.bottom); insets
        }
        setContentView(root); ViewCompat.requestApplyInsets(root)
        bindVoiceSession(intent)
        work.launch {
            withContext(Dispatchers.IO) { SettingsStore.init(this@GoalAssistantActivity) }
            settingsReady = true
            if (!voiceHidden) tell("No plan is running. Speech is on-device only in this host; text works on every supported version.")
            if (foreground) render()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recognition?.close(); recognition = null; invalidate()
        setIntent(intent)
        // Even a new system invocation is a fresh input surface, never a restored approval.
        goal.setText("")
        bindVoiceSession(intent)
        tell(if (voiceHidden) "The system voice session ended. Pending work was cancelled." else
            "New assistant invocation. Enter or speak a goal; no caller text or context was imported.")
    }
    private fun bindVoiceSession(source: Intent?) {
        AssistantSessionVisibility.currentSession.detach(voiceToken)
        voiceToken = runCatching { source?.getStringExtra(AssistantSessionVisibility.EXTRA_VISIBILITY_TOKEN) }.getOrNull()
        voiceHidden = false
        if (voiceToken != null) {
            val attached = AssistantSessionVisibility.currentSession.attach(voiceToken) {
                voiceHidden = true
                recognition?.close(); recognition = null; invalidate()
                tell("The system voice session ended. Microphone and pending approvals were cancelled. Invoke it again or explicitly use this workspace independently.")
            }
            if (!attached) voiceHidden = true
        }
    }
    override fun onResume() { super.onResume(); foreground = true; if (::plansView.isInitialized) render() }
    override fun onPause() {
        foreground = false; generation++; planning?.cancel(); recognition?.close(); recognition = null
        executing?.cancel(); executing = null
        session?.pause(); super.onPause()
    }
    override fun onStop() {
        foreground = false; generation++; planning?.cancel(); recognition?.close(); recognition = null
        executing?.cancel(); executing = null
        session?.pause(); super.onStop()
    }
    override fun onDestroy() {
        recognition?.close(); recognition = null
        AssistantSessionVisibility.currentSession.detach(voiceToken)
        session?.stop(); work.cancel(); super.onDestroy()
    }
    private fun accept(values: List<GoalPlan>) {
        session?.stop(); alternatives = values.map { it.detached() }; session = null
        tell("Choose a proposed plan. Permission and API claims from a model are not evidence."); render()
    }
    private fun localCommand() {
        if (!ready()) return
        val input = goal.text.toString().trim()
        if (!input.startsWith("do:")) return tell("Offline execution requires an explicit do: command. Free-form goals use the configured model.")
        val c = Command.parse(input.removePrefix("do:")) ?: return tell("Empty command.")
        val spec = Verbs.byId(c.verb)
        if (spec == null) {
            accept(listOf(GoalPlan(input, "Unresolved operation", listOf(GoalStep("s1", "missing.adapter",
                reason = "Find an integration for the requested command.", expected = input.take(2000)))))); return
        }
        if (c.positional.size > spec.params.size || c.named.keys.any { k -> spec.params.none { it.name == k } })
            return tell("Unexpected arguments. Nothing was executed.")
        val args = spec.params.mapNotNull { p -> c.arg(p.name)?.let { p.name to it } }.toMap()
        val p = GoalPlan(input, spec.label, listOf(GoalStep("s1", c.verb, args, expected = spec.help.take(2000))))
        if (p.validate().isNotEmpty()) return tell(p.validate().joinToString("\n"))
        accept(listOf(p))
    }
    private fun modelPlan() {
        if (!ready()) return
        val input = goal.text.toString().trim()
        if (input.isEmpty()) return tell("Describe the goal first.")
        val config = AiConfig.from(SettingsStore.current, task = com.example.core.ai.AiRequestTask.GOAL_PLAN)
        config.configurationProblem?.let { return tell(it) }
        if (!config.isUsable) return tell("Configure a model/provider in IO Matrix settings first. Explicit do: commands and light setup need no model.")
        AlertDialog.Builder(this).setTitle("Send this goal for planning?")
            .setMessage("Provider: ${config.provider}\nModel: ${config.model}\nEndpoint: ${runCatching { java.net.URI(config.effectiveBaseUrl).host }.getOrNull() ?: "configured endpoint"}\n\nSends the text you entered, action definitions and capability availability. No editor, clipboard, screen content or device addresses are included. A result only proposes a plan.")
            .setNegativeButton("Cancel", null).setPositiveButton("Plan") { _, _ ->
                if (!ready() || goal.text.toString().trim() != input) {
                    tell("The input or visible session changed. Review the goal again.")
                    return@setPositiveButton
                }
                invalidate(); val token = ++generation; val facts = snapshot(); val actions = GoalCatalogue.actions()
                tell("Requesting a proposal. Stop discards late results; a provider request already sent may still finish remotely.")
                planning = work.launch {
                    try {
                        val raw = AiClient.complete(config, GoalPrompt.system(actions.values, facts), input).getOrThrow()
                        ensureActive()
                        if (generation != token || goal.text.toString().trim() != input || !ready()) return@launch
                        val values = withContext(Dispatchers.Default) { GoalJson.read(raw, input) }
                        ensureActive(); if (generation == token && ready()) accept(values)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (generation == token) tell("Planning failed, or the model returned an invalid/oversized plan. Nothing was executed.") }
                }
            }.show()
    }
    private fun render() {
        plansView.removeAllViews()
        val s = session
        if (s == null) {
            alternatives.forEach { p ->
                plansView.addView(line("${p.title}\n" + p.steps.joinToString("\n") { "${it.id}: ${it.action} ${it.arguments}" }))
                plansView.addView(button("Inspect this plan") { session = GoalSession(p, GoalCatalogue.actions()); render() })
            }; return
        }
        plansView.addView(line("${s.plan.title}\n${if (s.stopped) "STOPPED" else if (s.allEffectsConfirmed()) "Every step confirmed (see evidence types below)" else "Not complete"}"))
        val facts = snapshot(); val states = s.states(); val notes = s.notes()
        s.plan.steps.forEach { step ->
            val check = GoalAssessment.step(step, GoalCatalogue.actions(), facts)
            plansView.addView(line(buildString {
                append("${step.id} · ${step.action} · ${states[step.id]}\n${step.arguments}\n")
                if (step.after.isNotEmpty()) append("Requires confirmed: ${step.after.joinToString()}\n")
                append("Reason: ${step.reason}\nExpected: ${step.expected}\n")
                check.issues.forEach { append("${it.state}: ${it.reason}\n") }
                if (step.apiHints.isNotEmpty()) append("Unverified API hints: ${step.apiHints.joinToString("; ")}\n")
                notes[step.id]?.let { append(it) }
            }))
            if (s.noteWasShortened(step.id) && GoalDispatchPolicy.runOnWorker(step))
                plansView.addView(button("Inspect this diagnostic in Phone tools") {
                    if (!ready() || session !== s) return@button
                    val tab = when (step.action) {
                        "sensors" -> "sensors"
                        "app_info" -> "apps"
                        else -> "overview"
                    }
                    runCatching { startActivity(Intent(this, PhoneToolsActivity::class.java).putExtra("tab", tab)) }
                        .onFailure { tell("Phone tools could not be opened on this device.") }
                })
            if (!s.stopped && states[step.id] == GoalSession.State.PENDING && s.dependenciesSatisfied(step.id))
                plansView.addView(button("Review ${step.id}") { review(s, step.id) }.apply { isEnabled = check.ready && ready() })
            if (!s.stopped && states[step.id] == GoalSession.State.DISPATCHED_UNVERIFIED)
                plansView.addView(button("I observed the effect…") {
                    AlertDialog.Builder(this).setTitle("Confirm actual effect")
                        .setMessage("Expected: ${step.expected}\n\nThis records your observation, not an Android/API verification. It allows dependent steps to be reviewed.")
                        .setNegativeButton("Not yet", null).setPositiveButton("I confirm") { _, _ ->
                            if (ready() && session === s && !s.stopped) { s.confirmObserved(step.id); render() }
                        }.show()
                })
        }
    }
    private fun review(s: GoalSession, id: String) {
        if (!ready() || session !== s) return
        val review = runCatching { s.review(id, snapshot(), now()) }.getOrNull() ?: return render()
        if (!review.feasible.ready) return render()
        AlertDialog.Builder(this).setTitle("Execute exactly this step?")
            .setMessage("${review.step.action}\n${review.step.arguments}\n\n${review.effects}\n\nVerification: ${review.verification}\n\nNo other step is authorized by this confirmation.")
            .setNegativeButton("Cancel", null).setPositiveButton("Execute") { _, _ ->
                if (!ready() || session !== s) return@setPositiveButton
                val ticket = s.begin(review, snapshot(), now()) ?: return@setPositiveButton tell("State changed or approval expired. Review again.")
                if (GoalDispatchPolicy.runOnWorker(ticket.step)) {
                    val token = generation
                    tell("Reading the selected local diagnostic. It will not be sent to the model.")
                    render()
                    executing = work.launch {
                        try {
                            val receipt = withContext(Dispatchers.IO) {
                                runCatching { GoalPerformer(this@GoalAssistantActivity).run(ticket.step) }.getOrElse {
                                    GoalSession.Receipt(GoalSession.State.FAILED, "The local diagnostic could not be read. No mutation was requested.")
                                }
                            }
                            ensureActive()
                            if (generation != token || !ready() || session !== s) return@launch
                            // complete verifies this exact active ticket/epoch, including any pause.
                            if (s.complete(ticket, receipt)) render()
                        } catch (e: CancellationException) { throw e }
                    }
                    return@setPositiveButton
                }
                val receipt = runCatching { GoalPerformer(this).run(ticket.step) }.getOrElse {
                    GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED, "Execution/readback was interrupted. Inspect the actual effect before continuing.")
                }
                s.complete(ticket, receipt); render()
            }.show()
    }
    private fun capabilities() {
        val facts = snapshot()
        val entries = Abilities.ALL
        AlertDialog.Builder(this).setTitle("Canonical phone capabilities")
            .setItems(entries.map { "${it.label} · ${facts.facts[it.id]?.state}" }.toTypedArray()) { _, index ->
                val a = entries[index]; val f = facts.facts.getValue(a.id)
                val d = AlertDialog.Builder(this).setTitle(a.label).setMessage("${f.state}\n${f.reason}\n\n${a.without}").setNegativeButton("Close", null)
                if (a.built && f.state != Readiness.READY) {
                    val intent = Abilities.settingsIntent(this, a)
                    if (intent != null) d.setPositiveButton("Open setup") { _, _ -> runCatching { startActivity(intent) }.onFailure { tell("Setup screen unavailable on this device.") } }
                    else (a.needs as? Need.Permission)?.let { n -> d.setPositiveButton("Request permission") { _, _ -> requestPermissions(arrayOf(n.name), 8810) } }
                }
                d.show()
            }.show()
    }
    private fun assistantRole() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            val role = getSystemService(RoleManager::class.java)
            if (role != null && role.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        } else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        AlertDialog.Builder(this).setTitle("Select IO Matrix as system assistant?")
            .setMessage("System assist opens this editable workspace. No recording, screen context or model request starts automatically.\n\nOn Android 12+ the voice service and bounded recognition relay use the installed on-device engine only; a downloaded language must be available. Earlier Android versions use the text ASSIST activity. There is no always-listening hotword or lockscreen execution in this build.\n\nThe system chooses what selection controls your device supports.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open system selection") { _, _ ->
                runCatching { startActivity(intent) }.onFailure { tell("Assistant selection is unavailable on this device.") }
            }.show()
    }
    private fun speak() {
        if (!ready()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8810)
            tell("After granting microphone access, press Speak again. No recording starts automatically."); return
        }
        recognition?.close()
        recognition = ForegroundSpeech(this, { ready() }, { words ->
            if (ready()) {
                recognition = null
                goal.setText(words); tell("Transcript only. Edit it and explicitly request a plan.")
            }
        }, { tell(it) }).also { it.start() }
    }
    @Suppress("DEPRECATION")
    private fun document(save: Boolean) {
        if (save && alternatives.isEmpty()) return tell("No proposed plan to save.")
        if (!save && goal.text.isBlank()) return tell("Enter the goal to bind the imported plan to first.")
        val intent = Intent(if (save) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
        if (save) intent.putExtra(Intent.EXTRA_TITLE, "IO-Matrix-plan.json")
        runCatching { startActivityForResult(intent, if (save) 8811 else 8812) }.onFailure { tell("Document picker unavailable.") }
    }
    @Deprecated("Platform Activity bridge for the API 24 host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(8811, 8812) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val input = goal.text.toString().trim(); val saved = alternatives.map { it.detached() }; val token = generation
        work.launch {
            try {
                if (requestCode == 8811) {
                    withContext(Dispatchers.IO) {
                        val raw = GoalJson.write(saved)
                        val out = contentResolver.openOutputStream(uri, "wt") ?: error("No stream")
                        out.bufferedWriter().use { it.write(raw) }
                    }; tell("Saved plan definitions only. Import never restores approval, permissions or execution status.")
                } else {
                    val result = withContext(Dispatchers.IO) {
                        val source = contentResolver.openInputStream(uri) ?: error("No stream")
                        val raw = source.bufferedReader().use { r ->
                            val buffer = CharArray(65537); var used = 0
                            while (used < buffer.size) { val n = r.read(buffer, used, buffer.size - used); if (n < 0) break; used += n }
                            require(used <= 65536); String(buffer, 0, used)
                        }; GoalJson.read(raw, input)
                    }
                    ensureActive()
                    if (token == generation && input == goal.text.toString().trim()) { invalidate(); accept(result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { tell("Plan import/save failed. No action was run; a failed save may leave a partial document.") }
        }
    }
}
