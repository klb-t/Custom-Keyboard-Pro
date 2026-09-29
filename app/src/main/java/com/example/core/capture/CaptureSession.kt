package com.example.core.capture

/** Android and browser adapters must validate the pinned target before EACH operation. */
interface CaptureDriver {
    fun frame(elapsed: Long, phase: String): CaptureFrame
    fun scroll(forward: Boolean): Boolean
    fun expand(attempted: MutableSet<String>): Boolean
}

/** One bounded step per timer tick; no threads, permissions or platform objects in the mechanism. */
class CaptureSession(val archive: ConversationArchive, private val driver: CaptureDriver) {
    enum class State { SEEKING, WAITING, CAPTURING, FINISHED }
    var state = if (archive.options.autoScroll && archive.options.seekStart) State.SEEKING else State.WAITING
        private set
    private var candidate: String? = null
    private var beforeScroll: String? = null
    private var lastSaved: String? = null
    private var latest: CaptureFrame? = null
    private var noProgress = 0
    private var seekSteps = 0
    private var seekLast: String? = null
    private var changedTicks = 0
    private var pendingExpansion = false
    private val attempted = mutableSetOf<String>()

    init { archive.startStatus = if (state == State.SEEKING) "seeking" else "current_position" }

    fun stop(reason: String = "user_stopped") {
        if (state == State.FINISHED) return
        if (archive.frames.isEmpty()) latest?.let { archive.append(it.copy(phase = "interrupted")) }
        archive.finish(reason)
        state = State.FINISHED
    }

    fun step(elapsed: Long): State {
        if (state == State.FINISHED) return state
        if (elapsed >= archive.options.maxMillis) { stop("time_limit"); return state }
        if (archive.full) { stop(if (archive.truncated) "content_limit" else "frame_limit"); return state }
        try {
            val f = driver.frame(elapsed, if (state == State.SEEKING) "seeking" else "collecting")
            latest = f
            val signature = f.fingerprint()
            if (state == State.SEEKING) {
                if (signature == seekLast) noProgress++ else noProgress = 0
                seekLast = signature
                seekSteps++
                if (f.clipped || noProgress >= 3 || seekSteps >= archive.options.maxFrames || !driver.scroll(false)) {
                    archive.startStatus = when {
                        f.clipped -> "seek_clipped"
                        seekSteps >= archive.options.maxFrames -> "seek_limit"
                        noProgress >= 3 -> "local_no_progress_not_proof_of_start"
                        else -> "backward_action_unavailable_not_proof_of_start"
                    }
                    noProgress = 0; state = State.WAITING; candidate = null
                }
                return state
            }
            // Two equal observations, separated by the caller's settle interval, before any action.
            if (candidate != signature) {
                candidate = signature
                changedTicks++
                // Streaming content must not disappear just because it never becomes quiet.
                if (changedTicks >= 6) {
                    archive.append(f.copy(phase = "changing_content_no_actions"))
                    changedTicks = 0
                }
                state = State.WAITING
                return state
            }
            changedTicks = 0
            if (beforeScroll != null) {
                if (beforeScroll == signature) noProgress++ else noProgress = 0
            }
            val afterScroll = beforeScroll != null
            beforeScroll = null
            if (!archive.options.autoScroll && !pendingExpansion && lastSaved != signature) attempted.clear()
            pendingExpansion = false
            if (lastSaved != signature || afterScroll) archive.append(f)
            lastSaved = signature
            state = State.CAPTURING
            if (archive.full) { stop(if (archive.truncated) "content_limit" else "frame_limit"); return state }
            if (noProgress >= 3) { stop("local_no_progress_completeness_unverified"); return state }
            if (archive.options.expandDetails && attempted.size < 32 && archive.expanded < 1000 && driver.expand(attempted)) {
                archive.expanded++; pendingExpansion = true; candidate = null
                return state
            }
            if (!archive.options.autoScroll) return state
            if (!driver.scroll(true)) { stop("forward_action_unavailable_or_boundary"); return state }
            beforeScroll = signature; candidate = null; attempted.clear()
        } catch (_: Exception) {
            // Platform errors can contain third-party strings; never put their messages in logs or exports.
            stop("target_changed_locked_service_unavailable_or_read_failed")
        }
        return state
    }
}
