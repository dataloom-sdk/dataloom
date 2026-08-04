from pathlib import Path

path = Path(__file__).with_name("dl_strategy_003_patch.py")
content = path.read_text()
old = '''    """protected remote-first execution, and bounded strategy-decision queue persistence
are implemented in common Kotlin. Room and Apple stores preserve the same
""",
    """protected remote-first execution, bounded strategy-decision queue persistence,
and fail-closed queued resolver correspondence are implemented in common
Kotlin. Room and Apple stores preserve the same
""",
'''
new = '''    """backed remote-first execution, and bounded strategy-decision queue persistence
are implemented in common Kotlin. Room and Apple stores preserve the same
""",
    """backed remote-first execution, bounded strategy-decision queue persistence,
and fail-closed queued resolver correspondence are implemented in common
Kotlin. Room and Apple stores preserve the same
""",
'''
if content.count(old) != 1:
    raise SystemExit("Expected one stale synchronization-strategy documentation anchor.")
path.write_text(content.replace(old, new, 1))
print("Corrected queued correspondence documentation anchor.")
