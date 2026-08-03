# DL-040 circuit-administration facade checkpoint

## Scope

This checkpoint records optional common `DataLoom` assembly for the qualified
circuit-administration coordinator. It does not claim complete observability,
platform fault injection, AC-FUNC-004 acceptance, or V1 readiness.

## Implemented boundary

- `DataLoomCircuitAdministration` exposes one narrow `execute(request)` method;
- `DataLoomCircuitAdministrationSpec` retains explicit authorizer, durable
  command store, platform executor, and bounded contention configuration;
- `DataLoomBuilder.circuitAdministrationConfiguration` assembles the existing
  coordinator with the runtime clock;
- `DataLoom.circuitAdministration` is nullable and source-compatible for custom
  pre-V1 facade implementations; and
- external JVM and Apple consumer probes compile the configuration and execution
  surface.

## Safety invariants

1. Build and property access perform no authorization, persistence, execution,
   clock read, provider initialization, identifier generation, or coroutine
   launch.
2. The facade does not expose the configured authorizer, state store, or
   executor.
3. The coordinator remains the sole owner of authorization, immutable command
   replay, durable audit ordering, and contention bounds.
4. Caller cancellation and exact coordinator results propagate unchanged.
5. Diagnostic rendering includes only the bounded attempt count and never
   collaborator implementation state.

## Focused evidence

Common builder tests cover absent configuration, side-effect-free assembly,
exact request/authorization forwarding, successful replay without duplicate
authorization or execution, positive contention validation, and redacted
diagnostics. The complete common runtime and focused tests compile under the
repository's exact Kotlin 2.4.10 compiler.

## Remaining DL-040 work

- retry/circuit events, bounded metrics, logs, traces, health, exporters, and
  an operational read model;
- executable relaunch, multi-process contention, and forced-failure injection;
  and
- complete Book 2 AC-FUNC-004 qualification on mandatory consumer paths.
