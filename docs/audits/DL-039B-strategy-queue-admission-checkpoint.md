# DL-039B strategy queue admission checkpoint

## Status

This bounded slice starts the durable strategy-plan path required by issue #102. It does not complete offline-first execution, queue persistence, platform parity, or the six-strategy gate.

## Problem

The deterministic strategy evaluator already returns an immutable `StrategyEvaluationResult`, and the public API already defines `PersistedStrategyDecision`. The runtime did not yet have one fail-closed boundary that decides whether an evaluated plan is eligible to enter durable queue admission and converts it into the exact bounded decision record that must survive retry, restart, lease recovery, and configuration changes.

Without that boundary, later queue integration could accidentally admit a rejected or non-queue plan, infer queue eligibility from strategy names, or reconstruct a different effective profile after restart.

## Implemented boundary

`StrategyQueueAdmissionEvaluator` is provider-free and deterministic. It:

- rejects a plan whose disposition is `REJECT`;
- requires the explicit `ENQUEUE_DURABLE_WORK` operation;
- requires the explicit `QUEUE` provider capability;
- preserves the exact decision ID, plan ID, requested strategy, effective profile, concrete effective strategy, configuration version, and disposition in `PersistedStrategyDecision`;
- performs no provider calls, I/O, clock reads, identifier generation, scheduling, retry evaluation, or mutation.

The focused common tests cover offline-first connectivity deferral, adaptive-to-concrete selection, rejected plans, missing queue operations, and missing queue capability.

## Security and diagnostics

The durable decision record is bounded and non-sensitive. It contains stable strategy identifiers and enum values only. It does not retain payloads, metadata, tenant or user identifiers, provider values, exception messages, credentials, or reason-code free text.

## Remaining work before merge can advance issue #102 materially

1. Carry the admitted decision through the queue-submission model and verify exact encoder correspondence before provider access.
2. Persist and recover the decision in the in-memory, Android Room, and Apple queue stores with migrations and corrupt partial-column rejection.
3. Require the queued resolver and execution handler to reuse the persisted decision and immutable plan reference rather than re-evaluate current configuration.
4. Prove preservation through retry, connectivity deferral, lease recovery, process termination/relaunch, and configuration rollback.
5. Qualify the same observable behavior through native Android, KMP Android, and KMP iOS reference flows.

## Acceptance boundary

This checkpoint is an internal runtime foundation only. It must not be represented as a completed strategy, completed offline-first flow, or platform-parity evidence.
