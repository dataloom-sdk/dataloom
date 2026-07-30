# DL-040 bounded retry-hint validation

## Scope

This checkpoint adds normalized, centrally bounded provider/server retry timing
guidance to the shared retry engine.

The shipped boundary consists of:

- `RetryDelayHint` and stable `SERVER` / `PROVIDER` source classifications;
- optional `RetryDelayHintCarrier` capability on canonical errors;
- `RetryEvaluationRequest.retryDelayHint` with a null compatibility default;
- `RetryHintConfiguration.maximumHintDelay` as the trust boundary;
- clamp-before-policy visibility;
- central `max(policyDelay, boundedHint)` enforcement for retry decisions; and
- elapsed/cumulative budget evaluation against the final adjusted delay.

Raw protocol headers, absolute dates, exception messages, provider instances,
payloads, credentials, and arbitrary metadata remain outside this contract.
Protocol adapters own normalization before constructing a typed hint.

## Focused evidence

The cleaned review head was produced after the following checks succeeded:

- exact JVM and Kotlin/Native ABI generation for model, API, runtime, testing,
  and Apple umbrella modules;
- model, API, runtime, and testing JVM tests;
- external-consumer compilation on JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- Apple XCFramework assembly; and
- public ABI checks for hint contracts and runtime configuration.

The temporary evidence workflow and helper script were removed before the final
review head. Permanent Pull Request, Android, and Apple validation remain the
authoritative merge gates.

## Remaining DL-040 gates

This checkpoint does not complete the V1 retry and circuit-breaker engine.
Remaining work includes timeout separation, durable closed/open/half-open circuit
state, controlled probes, authorized manual operations, complete retry
observability, and full platform qualification.
