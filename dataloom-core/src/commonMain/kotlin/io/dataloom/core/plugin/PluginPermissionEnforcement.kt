package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.security.Capability

/**
 * Maps one declared [PluginPermission] request label onto the least-privilege
 * foundation's [Capability] identity label.
 *
 * ## Why a 1:1 label mapping, not a new type
 *
 * `#93`'s least-privilege primitive ([Capability]/[io.dataloom.api.security.GrantedCapabilities]/
 * [io.dataloom.api.security.isAuthorized], `dataloom-model`) and `#93`'s plugin
 * SPI ([PluginPermission], `dataloom-plugin-api`) both ship the exact same
 * shape: a validated, non-blank, exactly-preserved string label with no
 * grant, scope, or expiry of its own. `dataloom-core` (this module) already
 * depends on both `dataloom-model` and `dataloom-plugin-api` directly — see
 * `docs/architecture/modules.md`'s `dataloom-core` dependency rule — so no
 * new module dependency, and no new policy-specific plumbing type, is needed
 * to connect them. A plugin's declared permission label *is* the capability
 * label checked against a caller-supplied grant.
 *
 * This is deliberately not a dependency from `dataloom-plugin-api` itself
 * onto `dataloom-model`'s security package — `dataloom-plugin-api` stays the
 * zero-behavior contract module it already is (see `docs/api/plugin-api.md`).
 * The mapping lives here, in `#98`'s own engine module, alongside the rest of
 * this gate's runtime behavior.
 */
public fun PluginPermission.asCapability(): Capability = Capability(value)
