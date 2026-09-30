package com.example.core.debug

import org.junit.Assert.*
import org.junit.Test

class RuntimeVariablesTest {
    @Test fun sensitiveReadersAreNeverCalled() {
        var read = false
        val r = RuntimeVariableRegistry()
        r.bind("test", listOf(RuntimeVariable("secret", "Secret", "Excluded", { read = true; "secret" }, sensitive = true)))
        assertEquals("[private]", r.snapshot().single().value)
        assertFalse(read)
    }
    @Test fun replacedContextRejectsOldEditsAndOldLeaseCannotDetachNewHost() {
        var value = "false"
        val r = RuntimeVariableRegistry()
        val v = RuntimeVariable("flag", "Flag", "Volatile", { value }, VariableRule.BooleanValue, { value = it })
        val old = r.bind("test", listOf(v))
        val preview = r.snapshot().single()
        r.bind("test", listOf(v))
        old.close()
        assertEquals(1, r.snapshot().size)
        assertTrue(r.write(preview, "true", true).isFailure)
        assertEquals("false", value)
    }
    @Test fun invalidationAndPermissionGateRejectWritesBeforeCallingHost() {
        var writes = 0
        val r = RuntimeVariableRegistry()
        val lease = r.bind("test", listOf(RuntimeVariable("flag", "Flag", "", { "false" }, VariableRule.BooleanValue, { writes++ })))
        val value = r.snapshot().single()
        assertTrue(r.write(value, "true", false).isFailure)
        assertTrue(r.write(value, "yes", true).isFailure)
        lease.invalidateContext()
        assertTrue(r.write(value, "true", true).isFailure)
        assertEquals(0, writes)
        assertTrue(r.write(r.snapshot().single(), "true", true).isSuccess)
        assertEquals(1, writes)
        lease.close()
        assertTrue(r.snapshot().isEmpty())
    }
    @Test fun descriptorMutationCannotExpandChoiceDomain() {
        val choices = mutableSetOf("base")
        val r = RuntimeVariableRegistry()
        r.bind("test", listOf(RuntimeVariable("layer", "Layer", "", { "base" }, VariableRule.Choice(choices), {})))
        choices.add("arbitrary")
        assertTrue(r.write(r.snapshot().single(), "arbitrary", true).isFailure)
    }
}
