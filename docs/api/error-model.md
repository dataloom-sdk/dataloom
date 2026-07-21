# DataLoom Error Model (DL-004)

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

## Sensitive-data restrictions

- Error messages must not include credentials, tokens, keys, or personal data.
- Examples in docs and tests use placeholder values only.
- The contract does not perform automatic logging.

## Provider-exception abstraction

`DataLoomError` exposes `Throwable?` as an optional cause, but the public
contract avoids binding consumers to any provider-specific exception taxonomy.
