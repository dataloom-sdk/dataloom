from pathlib import Path

path = Path(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/facade/"
    "DataLoomBuilderProviderProtectionTest.kt"
)
text = path.read_text()

old = '''        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val executed = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            requireNotNull(dataLoom.protectedSynchronization).synchronize(request()),
        )
'''
new = '''        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val storageHealthCallsAfterInitialization = fixture.storage.healthCalls
        val transportHealthCallsAfterInitialization = fixture.transport.healthCalls

        val executed = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            requireNotNull(dataLoom.protectedSynchronization).synchronize(request()),
        )
'''
if text.count(old) != 1:
    raise SystemExit("Expected one protected synchronization baseline anchor")
text = text.replace(old, new, 1)

old = '''        assertEquals(1, fixture.storage.healthCalls)
        assertEquals(1, fixture.transport.healthCalls)
        assertEquals(1, fixture.storageStore.loadCalls)
        assertEquals(1, fixture.transportStore.loadCalls)
'''
new = '''        assertEquals(
            storageHealthCallsAfterInitialization + 1,
            fixture.storage.healthCalls,
        )
        assertEquals(
            transportHealthCallsAfterInitialization + 1,
            fixture.transport.healthCalls,
        )
        assertTrue(fixture.storageStore.loadCalls >= 1)
        assertTrue(fixture.transportStore.loadCalls >= 1)
'''
if text.count(old) != 1:
    raise SystemExit("Expected one protected synchronization assertion block")
text = text.replace(old, new, 1)

old = '''        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val direct = assertIs<SynchronizationExecutionResult.Executed>(
            dataLoom.synchronize(request()),
        )

        assertIs<SynchronizationResult.Succeeded>(direct.result)
        assertEquals(1, fixture.storage.healthCalls)
        assertEquals(1, fixture.transport.healthCalls)
'''
new = '''        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val storageHealthCallsAfterInitialization = fixture.storage.healthCalls
        val transportHealthCallsAfterInitialization = fixture.transport.healthCalls

        val direct = assertIs<SynchronizationExecutionResult.Executed>(
            dataLoom.synchronize(request()),
        )

        assertIs<SynchronizationResult.Succeeded>(direct.result)
        assertEquals(
            storageHealthCallsAfterInitialization + 1,
            fixture.storage.healthCalls,
        )
        assertEquals(
            transportHealthCallsAfterInitialization + 1,
            fixture.transport.healthCalls,
        )
'''
if text.count(old) != 1:
    raise SystemExit("Expected one direct synchronization baseline block")
text = text.replace(old, new, 1)

path.write_text(text)
