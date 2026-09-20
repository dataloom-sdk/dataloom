package io.dataloom.runtime.facade

import io.dataloom.api.identifier.RuntimeVersion

/**
 * The version of the DataLoom runtime a [DataLoom] built by [DataLoomBuilder]
 * reports as "the running SDK".
 *
 * This is the single source of truth for that value. It is what plugin
 * compatibility is checked against (see
 * [DataLoomBuilder.pluginConfiguration]).
 *
 * The value is a hand-maintained constant, not derived from the build: no
 * Gradle project version or generated build-config value exists yet. It is a
 * pre-release development version and must be set to the real release version
 * by the release process (DL-046) before publication.
 */
public object DataLoomRuntimeVersion {

    /** The running SDK version, in canonical semantic-version form. */
    public val CURRENT: RuntimeVersion = RuntimeVersion("0.1.0")
}
