# Contributing to DataLoom

Thank you for your interest in DataLoom.

> **The project is currently in its foundation stage. Contributions are not yet open to the
> general public. These guidelines describe the process that will be followed once contributions
> are accepted.**

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Before You Start](#before-you-start)
- [Issue Requirements](#issue-requirements)
- [Branch Naming](#branch-naming)
- [Pull Request Requirements](#pull-request-requirements)
- [Commit Standards](#commit-standards)
- [Testing Requirements](#testing-requirements)
- [Documentation Requirements](#documentation-requirements)
- [Security Requirements](#security-requirements)
- [Architecture Requirements](#architecture-requirements)
- [Review and Merge Process](#review-and-merge-process)

---

## Code of Conduct

All contributors are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Violations may
result in removal from the project.

## Before You Start

1. Check that an approved GitHub issue exists for the change you want to make.
2. Comment on the issue to express your intent before starting work.
3. Wait for a maintainer to assign the issue to you.
4. Read the relevant sections of the project documentation.

Do not open a pull request for work that does not have an approved issue.

## Issue Requirements

Every pull request must close exactly one approved GitHub issue. Bundling multiple issues into a
single pull request is not permitted.

Changes that modify module boundaries or public APIs require additional architecture approval before
implementation begins. Open an architecture proposal issue and wait for approval.

## Branch Naming

Use the following naming patterns:

| Type | Pattern | Example |
|---|---|---|
| Feature | `feature/DL-NNN-short-description` | `feature/DL-002-gradle-skeleton` |
| Bug fix | `fix/DL-NNN-short-description` | `fix/DL-101-queue-recovery` |
| Documentation | `docs/DL-NNN-short-description` | `docs/DL-201-provider-guide` |
| Architecture | `arch/DL-NNN-short-description` | `arch/DL-050-conflict-model` |

Keep branch names lowercase and hyphen-separated.

## Pull Request Requirements

Every pull request must include:

- A reference to the related issue (e.g., `Closes #NNN`)
- A summary of what changed and why
- A list of files and modules changed
- Architecture impact assessment
- Public API compatibility impact assessment
- Security and privacy impact assessment
- Testing evidence (which tests were added or modified, and what they verify)
- Documentation changes
- Known limitations or follow-up issues

Use the pull request template provided in `.github/PULL_REQUEST_TEMPLATE.md`.

## Commit Standards

- Use the imperative mood in the commit subject (`Add queue retry`, not `Added queue retry`)
- Keep the subject line to 72 characters or fewer
- Reference the issue number in the commit message where relevant
- Do not include credentials, tokens, or sensitive information in commit messages

## Testing Requirements

Every change to production code must include appropriate tests. Tests must be:

- Deterministic: the same inputs always produce the same outputs
- Isolated: tests do not share state or depend on execution order
- Repeatable: tests pass consistently in any environment
- Independent: tests do not call production services or external systems
- Fast: unit tests complete in milliseconds; integration tests in seconds

Use fake clocks, test dispatchers, controlled providers, and explicit synchronization instead of
arbitrary `Thread.sleep` or `delay` calls.

Bug fixes must include a regression test that fails before the fix and passes after.

## Documentation Requirements

Update documentation whenever you change:

- Public API contracts
- Configuration options
- Module structure or boundaries
- Behavior that users depend on
- Examples

Every public API element must have KDoc that explains:

- Purpose
- Parameters and their valid ranges
- Return value
- Error conditions
- Cancellation behavior
- Thread-safety guarantees
- Usage example where helpful

## Security Requirements

**Never include the following in source code, tests, examples, issues, commit messages, or prompts:**

- Passwords or passphrases
- API keys or tokens
- Certificates or signing keys
- Private keys
- Personally identifiable information

Use placeholders (e.g., `YOUR_API_KEY`) in examples and tests. If you accidentally commit a
credential, notify the maintainers immediately via the security contact in [SECURITY.md](SECURITY.md).

## Architecture Requirements

Changes that affect the following require an approved architecture proposal before implementation:

- Module boundaries
- Public API contracts
- New external dependencies
- Coroutine dispatching or threading model
- Error type hierarchy
- Provider or plugin interfaces

Submit an architecture proposal issue using the architecture template and wait for explicit approval
from a maintainer before starting implementation.

## Review and Merge Process

1. Open a pull request using the provided template.
2. A maintainer will review the changes against the issue acceptance criteria.
3. Address all review feedback in new commits. Do not force-push during review.
4. A maintainer will merge the pull request once all requirements are satisfied.

**Do not merge your own pull request.**
**Do not publish packages or create releases.**

All publishing and release decisions require explicit human approval from project maintainers.
