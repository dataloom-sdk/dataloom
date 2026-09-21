package io.dataloom.platform.ios.lifecycle.internal

/**
 * Builds the production [ApplicationLifecycleSource]: a `dispatch`-backed main
 * thread executor, `NSNotificationCenter.defaultCenter` for the five
 * `UIApplication` lifecycle notifications, and a `UIApplication.applicationState`
 * reader.
 *
 * This is the one place (together with its `iosMain` actual) that touches
 * UIKit and Foundation notification APIs. The returned source registers
 * nothing until an observation starts.
 */
internal expect fun defaultApplicationLifecycleSource(): ApplicationLifecycleSource
