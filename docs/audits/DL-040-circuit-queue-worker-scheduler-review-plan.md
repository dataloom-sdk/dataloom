# DL-040 Scheduler Circuit Review Plan

This review branch integrates an independently configured scheduler circuit into
the already-qualified circuit-aware queue worker.

The review must reject any implementation that:

- reuses queue circuit state or scope as scheduler policy;
- applies circuit permission before the configured scheduler timeout boundary;
- converts an accepted `ScheduleReceipt` plus a failed circuit write into a
  generic scheduler failure;
- automatically submits the same accepted wake-up again;
- infers a scheduler provider, scope, state store, threshold, or classifier;
- performs provider, store, clock, timeout, or coroutine work during build; or
- changes the direct queue-worker scheduling behavior.

Merge requires focused JVM/iOS/ABI/XCFramework evidence and permanent Pull
Request, Android managed-device, and Apple/Swift validation on one clean final
head.
