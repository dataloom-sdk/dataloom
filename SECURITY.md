# Security policy

## Report a vulnerability privately

Do not disclose a suspected vulnerability in a public issue, discussion,
pull-request comment, commit message, or screenshot.

Use GitHub private vulnerability reporting for this repository. Include:

- the affected component and version or commit;
- a clear impact statement;
- minimal reproduction steps or a proof of concept;
- platform and environment details;
- whether credentials, personal data, tenant boundaries, durable state, or
  remote code are involved; and
- a suggested mitigation, if known.

Do not include live credentials, access tokens, signing material, private
customer payloads, or unnecessary personal data. Use synthetic values and
redacted evidence.

## Security boundaries

DataLoom treats these areas as security-sensitive:

- credential and authentication boundaries around transport providers;
- tenant identity and isolation;
- plugin identity, permissions, lifecycle, and supply chain;
- asset integrity, encryption, resumable-transfer metadata, and path handling;
- durable queue, retry, conflict, checkpoint, and audit records;
- event/log/metric/trace redaction; and
- artifact signing, dependency provenance, and release publication.

Applications remain responsible for acquiring credentials and defining domain
authorization. DataLoom must accept bounded provider interfaces and must not
log or persist secrets by default.

## Response process

Maintainers will triage reports through human review, establish severity and
affected versions, coordinate a bounded remediation, validate it locally, and
publish disclosure or release information when it is safe to do so.

No response-time or remediation-time service-level commitment is published
yet. A production V1 release requires a documented vulnerability-response and
release-signing process.

## Supported versions

DataLoom has not published a production V1 release. Until a supported-version
policy is published, reports should target the latest affected commit or
pre-release artifact and include that exact identifier.
