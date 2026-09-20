package io.dataloom.governance.rbac

import io.dataloom.governance.requireVocabularyIdentifier
import kotlin.jvm.JvmInline

/**
 * The closed set of coarse actions RBAC distinguishes.
 *
 * The enum is intentionally small; specificity lives in [ResourceType], not in
 * an ever-growing verb list. Actions are independent: no action implies
 * another (in particular [ADMINISTER] does not imply [READ]), so every grant
 * is explicit.
 *
 * Non-normative mapping of the FR-ENT-004 operations: view is [READ] on the
 * resource type being viewed; pause, resume, cancel, quarantine and
 * retry/requeue are [EXECUTE] on the resource type they act on; configure is
 * [WRITE] on a configuration resource type; export is [READ] on an export
 * resource type; support is [ADMINISTER] on a support resource type.
 */
public enum class Action {
    /** Observe or retrieve a resource without changing it. */
    READ,

    /** Create or modify a resource. */
    WRITE,

    /** Trigger an operation on a resource (for example pause, retry, cancel). */
    EXECUTE,

    /** Manage governance state for a resource type (for example grants or locks). */
    ADMINISTER,
}

/**
 * Identifier of a kind of governed resource, for example `queue`,
 * `retry.command`, `configuration` or `support.bundle`.
 *
 * Resource types are explicit tokens. There is no wildcard: the identifier
 * charset is `[A-Za-z0-9._:-]`, so `*` is not representable.
 */
@JvmInline
public value class ResourceType(
    /** Underlying resource-type identifier value. */
    public val value: String,
) {
    init {
        requireVocabularyIdentifier("ResourceType", value)
    }

    override fun toString(): String = value
}

/**
 * One exact grant or prohibition: an [action] on a [resourceType].
 *
 * Whether a [Permission] allows or denies depends on the [Role] set it is
 * placed in ([Role.allow] or [Role.deny]).
 */
public data class Permission(
    public val action: Action,
    public val resourceType: ResourceType,
)
