# Security Policy

## Supported Versions

> **DataLoom has not yet published a production release. No version is currently supported for
> security updates.**

Once production releases are available, this section will specify which versions receive security
patches.

## Reporting a Vulnerability

**Do not report security vulnerabilities through public GitHub issues, pull requests, or
discussions.** Public disclosure of a vulnerability before a fix is available puts all users at
risk.

To report a security vulnerability, contact the maintainers privately:

- Open a [GitHub Security Advisory](https://github.com/dataloom-sdk/dataloom/security/advisories/new)
  in this repository. This is the preferred reporting method.

Please include as much of the following information as possible to help understand and reproduce
the issue:

- Type of vulnerability (e.g., injection, credential exposure, insecure deserialization)
- Affected component or module
- Steps to reproduce or proof-of-concept
- Impact assessment
- Suggested remediation, if known

**Do not include real credentials, keys, tokens, or personally identifiable information in your
report.**

## Response Process

1. The maintainers will acknowledge receipt within 5 business days.
2. The maintainers will investigate and assess the severity.
3. A fix will be developed and reviewed privately.
4. A coordinated disclosure date will be agreed upon with the reporter.
5. The fix will be released and a public advisory will be published.

## Scope

The following are in scope for security reports:

- DataLoom SDK source code in this repository
- DataLoom public API contracts
- DataLoom documentation that contains misleading security guidance

The following are out of scope:

- Vulnerabilities in host applications that use DataLoom
- Vulnerabilities in third-party dependencies (report these upstream)
- Social engineering attacks

## Security Design Principles

DataLoom is designed with the following security principles:

- Credentials, tokens, and keys are never logged or included in diagnostic output
- Sensitive user data is never exposed through SDK-level error messages
- SDK modules follow least-privilege boundaries
- No credentials are ever required in SDK configuration
- Authentication and encryption are the responsibility of the host application and its backend
