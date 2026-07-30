# DL-040 timeout-boundary checkpoint

PR #113 separates connection, request, idle, provider, policy, and workflow timeout contracts.

The timeout coordinator preserves unconfigured boundaries, propagates workflow deadlines, caps a boundary timeout to the remaining workflow window, rejects expired workflows before invoking a platform executor, and treats wall-clock regression as a fail-closed result.

The platform executor contract must interrupt or cancel timed operations and must allow caller cancellation to propagate without translating it into a timeout result.

Focused validation completed for runtime JVM tests, external JVM and iOS consumers, exact JVM/KLib ABI generation, and Apple XCFramework assembly. Permanent pull-request, Android, and Apple checks remain authoritative for the final review head.
