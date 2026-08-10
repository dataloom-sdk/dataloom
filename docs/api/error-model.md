# DataLoom Error Model (DL-004)

[API reference index](./README.md)

> **Status:** Available public contract. The complete diagnostic catalogue,
> enforcement, redaction, and operational mapping remain V1 work.

This document defines the canonical public error model introduced in
`dataloom-api`.

The model standardizes error metadata but does **not** implement runtime error
mapping, retry strategy, or automatic recovery behavior.

## ErrorCode

`ErrorCode` is a stable machine-readable identifier.

Rules for current and future codes:

- Error codes are stable identifiers, not user-facing localized messages.
- Error codes must be non-blank.
- Error codes must not contain credentials or user data.
- Released codes must not silently change meaning.
- Codes must not be generated from exception class names.
- Provider-specific mapping can be added in a later issue.
- The complete canonical code catalogue is intentionally deferred.

Placeholder example:

```kotlin
val code = ErrorCode("DL-ERROR-VALIDATION")
```

## DataLoomError

`DataLoomError` is the canonical public contract:

- `code: ErrorCode`
- `category: ErrorCategory`
- `severity: ErrorSeverity`
- `recoverability: Recoverability`
- `message: String`
- `cause: Throwable?`

`cause` may preserve diagnostics, but consumers are not required to depend on
provider-specific exception types.

## Error categories

`ErrorCategory` provides technology-neutral classification:

- `NETWORK`
- `STORAGE`
- `AUTHENTICATION`
- `AUTHORIZATION`
- `SERIALIZATION`
- `VALIDATION`
- `CONFIGURATION`
- `QUEUE`
- `SCHEDULER`
- `POLICY`
- `CONFLICT`
- `STATE`
- `PROVIDER`
- `PLUGIN`
- `SECURITY`
- `INTERNAL`

## Error severity

`ErrorSeverity` describes impact:

- `WARNING`: a meaningful problem occurred, but processing may continue or recover.
- `ERROR`: an operation failed and requires handling.
- `CRITICAL`: integrity, security, or runtime viability may be at risk.

## Recoverability

`Recoverability` describes expected recovery potential:

- `RECOVERABLE`: retry or another recovery action may succeed.
- `NON_RECOVERABLE`: repeating without correction is not expected to succeed.
- `UNKNOWN`: recoverability cannot yet be safely classified.

## Severity vs. recoverability

Severity and recoverability are independent signals:

- Severity communicates impact.
- Recoverability communicates whether another attempt may succeed.

Neither field alone defines retry policy in this issue.

## Optional retry timing guidance

A recoverable error may additionally implement `RetryDelayHintCarrier`. The
carrier exposes a typed `RetryDelayHint` containing only a non-negative delay in
milliseconds and a stable `SERVER` or `PROVIDER` source.

Protocol adapters own parsing raw values such as HTTP `Retry-After`. They must
normalize absolute dates or protocol units before creating the hint. The shared
runtime never parses raw headers, exception messages, or provider-specific text.
A hint remains untrusted until bounded by `RetryHintConfiguration`.

## Sensitive-data restrictions

- Error messages must not include credentials, tokens, keys, or personal data.
- Examples in docs and tests use placeholder values only.
- The contract does not perform automatic logging.

**Enforcement status:** `message` content is not automatically sanitized —
this is a documented convention each `DataLoomError` implementation must
uphold when it constructs its own `message`, not a runtime check. A known
gap: some existing provider error mappers (for example the Apollo GraphQL
transport's error mapper) forward a wrapped exception's own `.message`
into `message` after only *truncating* it to a bounded length, which is not
the same as redacting sensitive content — a wrapped HTTP client exception's
message commonly embeds the request URL, which may carry a token in a query
parameter. Closing that gap requires an actual content-classification/
redaction strategy (regex/allowlist-based scrubbing, or switching those call
sites to fully generic classified messages with no raw exception text at
all) and is tracked as separate follow-up work under `#93`, not fixed by
this section.

## Safe default rendering

Every current `DataLoomError` implementation across this codebase overrides
`toString()` with `safeDiagnosticString()`:

```kotlin
override fun toString(): String = safeDiagnosticString()
```

This exists because a plain `data class`'s auto-generated `toString()`
renders every constructor property — including `cause: Throwable?` — via
that property's *own* `toString()`. A wrapped third-party or platform
exception is not classified by this codebase and cannot be assumed safe to
print by default; `safeDiagnosticString()` renders `cause` as its exception
*type name* only, never its message or `toString()`, so that relying on the
default `toString()` (a very common path — `println(error)`, naive
`logger.info(error.toString())`, or simply an auto-generated data class
`toString()` nobody thought to review) cannot leak wrapped-exception content
by accident. Code paths that genuinely need the full cause chain (a
debugger, an explicit diagnostic export) can still read
`DataLoomError.cause` directly — `safeDiagnosticString()` only bounds what
happens *by default*. See `DataLoomErrorRendering.kt` in `dataloom-model`.

This does not sanitize `message` itself — see "Enforcement status" above.

## Provider-exception abstraction

`DataLoomError` exposes `Throwable?` as an optional cause, but the public
contract avoids binding consumers to any provider-specific exception taxonomy.
