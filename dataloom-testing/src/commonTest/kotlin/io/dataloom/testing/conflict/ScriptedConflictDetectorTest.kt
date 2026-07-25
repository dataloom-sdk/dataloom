package io.dataloom.testing.conflict

import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.testing.conflictDetectorId
import io.dataloom.testing.sampleConflict
import io.dataloom.testing.sampleConflictDetectionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedConflictDetectorTest {
    @Test
    fun `returns scripted results in order`() {
        val first = ConflictDetectionResult.NoConflict
        val second = ConflictDetectionResult.ConflictDetected(sampleConflict())
        val detector = ScriptedConflictDetector(conflictDetectorId(), mutableListOf(first, second))
        assertEquals(first, detector.detect(sampleConflictDetectionRequest("001")))
        assertEquals(second, detector.detect(sampleConflictDetectionRequest("002")))
    }

    @Test
    fun `fallback is used when script is exhausted`() {
        val fallback = ConflictDetectionResult.NoConflict
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = fallback)
        assertEquals(fallback, detector.detect(sampleConflictDetectionRequest()))
    }

    @Test
    fun `exhaustion without fallback throws informative exception`() {
        val detector = ScriptedConflictDetector(conflictDetectorId("detector-empty"))
        val error = assertFailsWith<IllegalStateException> {
            detector.detect(sampleConflictDetectionRequest())
        }
        assertEquals(true, error.message.orEmpty().contains("detector-empty"))
    }

    @Test
    fun `records detection requests`() {
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = ConflictDetectionResult.NoConflict)
        val first = sampleConflictDetectionRequest("001")
        val second = sampleConflictDetectionRequest("002")
        detector.detect(first)
        detector.detect(second)
        assertEquals(listOf(first, second), detector.detectionRequests)
    }

    @Test
    fun `enqueue result appends scripted results`() {
        val detector = ScriptedConflictDetector(conflictDetectorId())
        val result = ConflictDetectionResult.ConflictDetected(sampleConflict())
        detector.enqueueResult(result)
        assertEquals(result, detector.detect(sampleConflictDetectionRequest()))
    }

    @Test
    fun `clear recordings preserves results`() {
        val detector = ScriptedConflictDetector(conflictDetectorId())
        val result = ConflictDetectionResult.NoConflict
        detector.enqueueResult(result)
        detector.clearRecordings()
        assertEquals(result, detector.detect(sampleConflictDetectionRequest()))
    }

    @Test
    fun `clear recordings empties request log`() {
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = ConflictDetectionResult.NoConflict)
        detector.detect(sampleConflictDetectionRequest())
        detector.clearRecordings()
        assertEquals(emptyList(), detector.detectionRequests)
    }

    @Test
    fun `reset state clears results and recordings`() {
        val detector = ScriptedConflictDetector(conflictDetectorId())
        detector.enqueueResult(ConflictDetectionResult.NoConflict)
        detector.detect(sampleConflictDetectionRequest())
        detector.resetState()
        assertEquals(emptyList(), detector.detectionRequests)
        assertFailsWith<IllegalStateException> { detector.detect(sampleConflictDetectionRequest("again")) }
    }

    @Test
    fun `id is exposed unchanged`() {
        val id = conflictDetectorId("detector-123")
        val detector = ScriptedConflictDetector(id, fallback = ConflictDetectionResult.NoConflict)
        assertEquals(id, detector.id)
    }

    @Test
    fun `requests are recorded before fallback is used`() {
        val request = sampleConflictDetectionRequest()
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = ConflictDetectionResult.NoConflict)
        detector.detect(request)
        assertEquals(listOf(request), detector.detectionRequests)
    }

    @Test
    fun `requests are recorded before exhaustion failure`() {
        val request = sampleConflictDetectionRequest()
        val detector = ScriptedConflictDetector(conflictDetectorId())
        assertFailsWith<IllegalStateException> { detector.detect(request) }
        assertEquals(listOf(request), detector.detectionRequests)
    }

    @Test
    fun `constructor results are used before enqueued results`() {
        val first = ConflictDetectionResult.NoConflict
        val second = ConflictDetectionResult.ConflictDetected(sampleConflict())
        val detector = ScriptedConflictDetector(conflictDetectorId(), results = mutableListOf(first))
        detector.enqueueResult(second)
        assertEquals(first, detector.detect(sampleConflictDetectionRequest("001")))
        assertEquals(second, detector.detect(sampleConflictDetectionRequest("002")))
    }

    @Test
    fun `supports conflict detected fallback`() {
        val fallback = ConflictDetectionResult.ConflictDetected(sampleConflict())
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = fallback)
        assertEquals(fallback, detector.detect(sampleConflictDetectionRequest()))
    }

    @Test
    fun `multiple clear recordings calls are safe`() {
        val detector = ScriptedConflictDetector(conflictDetectorId(), fallback = ConflictDetectionResult.NoConflict)
        detector.clearRecordings()
        detector.clearRecordings()
        assertEquals(emptyList(), detector.detectionRequests)
    }

    @Test
    fun `reset state does not change id`() {
        val id = conflictDetectorId("detector-reset")
        val detector = ScriptedConflictDetector(id, fallback = ConflictDetectionResult.NoConflict)
        detector.resetState()
        assertEquals(id, detector.id)
    }
}
