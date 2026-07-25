package io.dataloom.testing.conflict

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.conflictResolverId
import io.dataloom.testing.sampleConflictResolutionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedConflictResolverTest {
    private fun useLocal(): ConflictResolutionDecision = ConflictResolutionDecision.UseLocal()
    private fun useRemote(): ConflictResolutionDecision = ConflictResolutionDecision.UseRemote()
    private fun failDecision(): ConflictResolutionDecision = ConflictResolutionDecision.Fail(FakeDataLoomError())

    @Test
    fun `returns scripted decisions in order`() {
        val resolver = ScriptedConflictResolver(conflictResolverId(), mutableListOf(useLocal(), useRemote()))
        assertEquals(useLocal(), resolver.resolve(sampleConflictResolutionRequest("001")))
        assertEquals(useRemote(), resolver.resolve(sampleConflictResolutionRequest("002")))
    }

    @Test
    fun `fallback is used when script is exhausted`() {
        val fallback = failDecision()
        val resolver = ScriptedConflictResolver(conflictResolverId(), fallback = fallback)
        assertEquals(fallback, resolver.resolve(sampleConflictResolutionRequest()))
    }

    @Test
    fun `exhaustion without fallback throws informative exception`() {
        val resolver = ScriptedConflictResolver(conflictResolverId("resolver-empty"))
        val error = assertFailsWith<IllegalStateException> {
            resolver.resolve(sampleConflictResolutionRequest())
        }
        assertEquals(true, error.message.orEmpty().contains("resolver-empty"))
    }

    @Test
    fun `records resolution requests`() {
        val resolver = ScriptedConflictResolver(conflictResolverId(), fallback = useLocal())
        val first = sampleConflictResolutionRequest("001")
        val second = sampleConflictResolutionRequest("002")
        resolver.resolve(first)
        resolver.resolve(second)
        assertEquals(listOf(first, second), resolver.resolutionRequests)
    }

    @Test
    fun `enqueue decision appends scripted decisions`() {
        val resolver = ScriptedConflictResolver(conflictResolverId())
        val decision = useLocal()
        resolver.enqueueDecision(decision)
        assertEquals(decision, resolver.resolve(sampleConflictResolutionRequest()))
    }

    @Test
    fun `clear recordings preserves scripted decisions`() {
        val resolver = ScriptedConflictResolver(conflictResolverId())
        val decision = useRemote()
        resolver.enqueueDecision(decision)
        resolver.clearRecordings()
        assertEquals(decision, resolver.resolve(sampleConflictResolutionRequest()))
    }

    @Test
    fun `clear recordings empties request log`() {
        val resolver = ScriptedConflictResolver(conflictResolverId(), fallback = useLocal())
        resolver.resolve(sampleConflictResolutionRequest())
        resolver.clearRecordings()
        assertEquals(emptyList(), resolver.resolutionRequests)
    }

    @Test
    fun `reset state clears decisions and recordings`() {
        val resolver = ScriptedConflictResolver(conflictResolverId())
        resolver.enqueueDecision(useLocal())
        resolver.resolve(sampleConflictResolutionRequest())
        resolver.resetState()
        assertEquals(emptyList(), resolver.resolutionRequests)
        assertFailsWith<IllegalStateException> { resolver.resolve(sampleConflictResolutionRequest("again")) }
    }

    @Test
    fun `id is exposed unchanged`() {
        val id = conflictResolverId("resolver-123")
        val resolver = ScriptedConflictResolver(id, fallback = useLocal())
        assertEquals(id, resolver.id)
    }

    @Test
    fun `requests are recorded before fallback is used`() {
        val request = sampleConflictResolutionRequest()
        val resolver = ScriptedConflictResolver(conflictResolverId(), fallback = useLocal())
        resolver.resolve(request)
        assertEquals(listOf(request), resolver.resolutionRequests)
    }

    @Test
    fun `requests are recorded before exhaustion failure`() {
        val request = sampleConflictResolutionRequest()
        val resolver = ScriptedConflictResolver(conflictResolverId())
        assertFailsWith<IllegalStateException> { resolver.resolve(request) }
        assertEquals(listOf(request), resolver.resolutionRequests)
    }

    @Test
    fun `constructor decisions are used before enqueued decisions`() {
        val resolver = ScriptedConflictResolver(conflictResolverId(), decisions = mutableListOf(useLocal()))
        resolver.enqueueDecision(useRemote())
        assertEquals(useLocal(), resolver.resolve(sampleConflictResolutionRequest("001")))
        assertEquals(useRemote(), resolver.resolve(sampleConflictResolutionRequest("002")))
    }

    @Test
    fun `supports fail decision fallback`() {
        val fallback = failDecision()
        val resolver = ScriptedConflictResolver(conflictResolverId(), fallback = fallback)
        assertEquals(fallback, resolver.resolve(sampleConflictResolutionRequest()))
    }

    @Test
    fun `supports defer decision`() {
        val decision = ConflictResolutionDecision.Defer()
        val resolver = ScriptedConflictResolver(conflictResolverId(), decisions = mutableListOf(decision))
        assertEquals(decision, resolver.resolve(sampleConflictResolutionRequest()))
    }

    @Test
    fun `reset state does not change id`() {
        val id = conflictResolverId("resolver-reset")
        val resolver = ScriptedConflictResolver(id, fallback = useLocal())
        resolver.resetState()
        assertEquals(id, resolver.id)
    }
}
