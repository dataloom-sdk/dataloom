package io.dataloom.apple

/**
 * Internal marker object for the dataloom-apple distribution module.
 *
 * This file satisfies the requirement for a non-empty commonMain source set
 * during XCFramework assembly.  It contains no synchronization logic,
 * no provider implementation, no global singleton, and no service locator.
 *
 * The DataLoom public API is provided by the model, provider API, SDK API, and
 * runtime modules exported by the XCFramework. Swift consumers import
 * DataLoom and access the facade and builder through that single framework.
 */
internal object DataLoomAppleModule
