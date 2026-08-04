from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
README = ROOT / "README.md"

content = README.read_text()


def replace_once(old: str, new: str) -> None:
    global content
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected one README match, found {count}: {old[:120]!r}")
    content = content.replace(old, new, 1)


replace_once(
    """Plan-aware provider resolution and direct network-only/remote-first operation
execution are implemented. Durable decision persistence, the remaining strategy
runtimes, and complete platform qualification remain required before the
strategy engine is complete.
""",
    """Plan-aware provider resolution and direct network-only/remote-first operation
execution are implemented. Bounded strategy-decision identity is preserved by
the in-memory, Android Room, and Apple durable queue stores. Immutable accepted
execution-plan replay, the remaining strategy runtimes, and complete platform
qualification remain required before the strategy engine is complete.
""",
)
replace_once("- **Last reconciled:** 2026-08-03", "- **Last reconciled:** 2026-08-04")
replace_once(
    "- **Accepted engineering/release gates:** **0 of 10** (`#93`–`#102` are open)",
    "- **Accepted engineering/release gates:** **1 of 10** "
    "(`#93` complete; `#94`–`#102` open)",
)

lines = content.splitlines()
row_updates = {
    "DL-039 foundations, artifacts, compatibility": ("—", "COMPLETE", "—"),
    "DL-039B six strategy engine": ("1", None, None),
    "DL-039A Android/KMP/iOS parity": ("2", None, None),
    "DL-040 retry and circuit breaker": ("3", None, None),
    "DL-041 conflict engine": ("4", None, None),
    "DL-042 events, observability, health, dashboard": ("5", None, None),
    "DL-043 asset synchronization": ("6", None, None),
    "DL-044 plugin platform": ("7", None, None),
    "DL-045 enterprise governance": ("8", None, None),
    "DL-046 immutable V1 release": ("9", None, None),
}
seen: set[str] = set()
for index, line in enumerate(lines):
    for label, (priority, status, pending) in row_updates.items():
        if label not in line:
            continue
        if label in seen:
            raise SystemExit(f"Duplicate README dashboard row: {label}")
        parts = line.split("|")
        if len(parts) != 7:
            raise SystemExit(f"Unexpected dashboard row shape for {label}: {len(parts)}")
        parts[1] = f" {priority} "
        if status is not None:
            parts[3] = f" {status} "
        if pending is not None:
            parts[5] = f" {pending} "
        lines[index] = "|".join(parts)
        seen.add(label)

missing = set(row_updates) - seen
if missing:
    raise SystemExit(f"Missing README dashboard rows: {sorted(missing)}")
content = "\n".join(lines) + "\n"

start_marker = "### Immediate execution order\n"
end_marker = "Detailed evidence lives in the\n"
start = content.index(start_marker)
end = content.index(end_marker, start)
new_order = """### Immediate execution order

Closed foundation gate #93 remains the single shared configuration, policy,
state, security, compatibility, and event boundary for every remaining engine.

1. Deliver one end-to-end strategy/platform vertical slice across native
   Android, KMP Android, and KMP iOS, then complete all six strategies through
   the same architecture (#102 + #101).
2. Add the host-controlled process lifecycle and cross-process harness needed
   to close the remaining retry/circuit acceptance criteria in #94.
3. Complete conflict, durable events/operations, assets, plugins, and enterprise
   governance in dependency order (#95–#99).
4. Build the three staged-artifact reference apps and publish the benchmark and
   fault-injection evidence.
5. Run customer validation alongside engineering: interviews, design partners,
   pilots, then one paid pilot.
6. Build and qualify one immutable V1 candidate and promote that exact artifact
   only after every engineering, security, legal, and market gate passes (#100).

"""
content = content[:start] + new_order + content[end:]

README.write_text(content)

checkpoint = ROOT / "docs/audits/DL-039B-durable-strategy-decision-persistence-checkpoint.md"
checkpoint_content = checkpoint.read_text().rstrip()
checkpoint_content += """

## Dashboard reconciliation

The repository landing page now records closed foundation gate #93 as the first
accepted V1 gate, renumbers the remaining dependency order, and distinguishes
durable strategy-decision identity from the still-missing immutable execution-
plan replay and complete strategy runtimes.
"""
checkpoint.write_text(checkpoint_content.rstrip() + "\n")

print("Reconciled the DataLoom market-readiness dashboard.")
