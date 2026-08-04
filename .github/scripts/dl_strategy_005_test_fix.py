from pathlib import Path

path = Path(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
content = path.read_text()
old_import = "import io.dataloom.api.synchronization.ChangeSetAcknowledgement\n"
new_import = """import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
"""
if content.count(old_import) != 1:
    raise SystemExit("Expected one acknowledgement import anchor.")
content = content.replace(old_import, new_import, 1)
old_ack = """                ChangeSetAcknowledgement(
                    changeSetId = request.changeSet.id,
                    events = emptyList(),
                ),
"""
new_ack = """                ChangeSetAcknowledgement(
                    changeSetId = request.changeSet.id,
                    events = listOf(
                        ChangeEventAcknowledgement(
                            eventId = ChangeEventId("event-ack"),
                            status = ChangeAcknowledgementStatus.ACCEPTED,
                        ),
                    ),
                ),
"""
if content.count(old_ack) != 1:
    raise SystemExit("Expected one acknowledgement fixture.")
path.write_text(content.replace(old_ack, new_ack, 1))
print("Corrected accepted-plan acknowledgement fixture.")
