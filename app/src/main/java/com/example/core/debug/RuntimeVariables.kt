package com.example.core.debug

/** Runtime inspection is explicit registration, never reflection over credentials or editor text. */
sealed interface VariableRule {
    fun accepts(value: String): Boolean
    data object BooleanValue : VariableRule {
        override fun accepts(value: String) = value == "true" || value == "false"
    }
    data class Choice(val values: Set<String>) : VariableRule {
        override fun accepts(value: String) = value in values
    }
    data class Decimal(val min: Double, val max: Double) : VariableRule {
        override fun accepts(value: String) = value.toDoubleOrNull()?.let { it.isFinite() && it in min..max } == true
    }
}

data class RuntimeVariable(
    val id: String,
    val label: String,
    val description: String,
    val read: () -> String,
    val rule: VariableRule? = null,
    val write: ((String) -> Unit)? = null,
    val sensitive: Boolean = false
)

data class RuntimeValue(
    val owner: String,
    val epoch: Long,
    val id: String,
    val label: String,
    val description: String,
    val value: String,
    val rule: VariableRule?,
    val writable: Boolean
)

/** Call readers/writers on the host's thread. Epochs prevent edits to a replaced editor/session. */
class RuntimeVariableRegistry {
    private data class Host(val epoch: Long, val variables: List<RuntimeVariable>)
    private val hosts = linkedMapOf<String, Host>()
    private var serial = 0L

    inner class Lease internal constructor(private val owner: String, private var epoch: Long) : AutoCloseable {
        @Synchronized fun invalidateContext() {
            synchronized(this@RuntimeVariableRegistry) {
                val host = hosts[owner]?.takeIf { it.epoch == epoch } ?: return
                epoch = ++serial
                hosts[owner] = host.copy(epoch = epoch)
            }
        }
        override fun close() {
            synchronized(this@RuntimeVariableRegistry) {
                if (hosts[owner]?.epoch == epoch) hosts.remove(owner)
            }
        }
    }

    @Synchronized fun bind(owner: String, variables: List<RuntimeVariable>): Lease {
        require(owner.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        require(variables.size <= 256)
        require(variables.map { it.id }.distinct().size == variables.size)
        variables.forEach {
            require(it.id.matches(Regex("[a-z][a-z0-9_.-]{0,127}")))
            require(it.label.length <= 160 && it.description.length <= 1000)
            require(it.write == null || it.rule != null)
            require(!it.sensitive || it.write == null)
        }
        val epoch = ++serial
        // Detach mutable collections; editing the original descriptor list cannot add a writer.
        val detached = variables.map { v ->
            v.copy(rule = when (val r = v.rule) {
                is VariableRule.Choice -> VariableRule.Choice(java.util.Collections.unmodifiableSet(r.values.toSet()))
                else -> r
            })
        }
        hosts[owner] = Host(epoch, detached)
        return Lease(owner, epoch)
    }

    @Synchronized fun snapshot(): List<RuntimeValue> = hosts.flatMap { (owner, host) ->
        host.variables.map { variable ->
            RuntimeValue(owner, host.epoch, variable.id, variable.label, variable.description,
                if (variable.sensitive) "[private]" else runCatching { variable.read().take(1000) }.getOrDefault("[unavailable]"),
                if (variable.sensitive) null else variable.rule,
                !variable.sensitive && variable.write != null)
        }
    }

    @Synchronized fun write(value: RuntimeValue, next: String, allowed: Boolean): Result<Unit> = runCatching {
        require(allowed) { "Select Debugger level to edit runtime state." }
        val host = hosts[value.owner]?.takeIf { it.epoch == value.epoch }
            ?: error("Runtime context changed. Refresh before editing.")
        val variable = host.variables.singleOrNull { it.id == value.id } ?: error("Variable no longer exists.")
        require(!variable.sensitive && variable.write != null) { "This variable is read-only." }
        require(next.length <= 256 && variable.rule?.accepts(next) == true) { "Value is outside the declared domain." }
        try { variable.write.invoke(next) } catch (_: Exception) { error("The host refused the runtime change.") }
    }
}

object RuntimeVariables { val registry = RuntimeVariableRegistry() }
