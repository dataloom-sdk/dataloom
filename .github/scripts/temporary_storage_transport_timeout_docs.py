from pathlib import Path


path = Path("docs/api/README.md")
text = path.read_text()
old = """| [Retry timeout boundaries](./retry-timeouts.md) | Partial V1 subsystem | Independent timeout contracts, workflow-deadline precedence, coroutine executor, and selected provider/runtime assembly. |
| [Circuit breaker](./circuit-breaker.md) | Partial V1 subsystem | Explicit scopes, durable state contracts, atomic compare-and-set persistence, deterministic transitions, and one controlled half-open probe. |
"""
new = """| [Retry timeout boundaries](./retry-timeouts.md) | Partial V1 subsystem | Independent timeout contracts, workflow-deadline precedence, coroutine executor, and selected provider/runtime assembly. |
| [Storage and transport provider timeouts](./storage-transport-provider-timeouts.md) | Partial V1 subsystem | Cooperative lifecycle and synchronization-operation timeout protection with fail-closed mutation ambiguity. |
| [Circuit breaker](./circuit-breaker.md) | Partial V1 subsystem | Explicit scopes, durable state contracts, atomic compare-and-set persistence, deterministic transitions, and one controlled half-open probe. |
"""
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected one retry timeout index block, found {count}")
path.write_text(text.replace(old, new, 1))
