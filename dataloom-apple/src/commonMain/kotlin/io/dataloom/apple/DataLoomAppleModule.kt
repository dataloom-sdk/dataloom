package io.dataloom.apple

/**
 * Internal marker object for the dataloom-apple distribution module.
 *
 * This file satisfies the requirement for a non-empty commonMain source set
 * during XCFramework assembly.  It contains no synchronization logic,
 * no provider implementation, no global singleton, and no service locator.
 *
 * The DataLoom public API is provided by dataloom-api, dataloom-core, and
 * dataloom-runtime.  Swift consumers import DataLoom and access the facade
 * and builder from those modules.
 */
internal object DataLoomAppleModule
