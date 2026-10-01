@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.assets.file

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.time
import platform.posix.timeval
import platform.posix.utimes

/**
 * A fresh, unique absolute directory path under the process's temp
 * directory, for tests only — mirroring
 * `dataloom-runtime`'s `AppleFileQueueProviderLifecycleTest.uniqueDirectory`.
 * `NSTemporaryDirectory`/`NSUUID` (Foundation) are used here only because
 * that is the established precedent for *test* scaffolding on Apple in this
 * codebase; production code in this package ([AppleFileAssetIo] and friends)
 * uses POSIX only.
 */
internal fun uniqueTestDirectory(prefix: String): String = buildString {
    append(NSTemporaryDirectory().trimEnd('/'))
    append('/')
    append(prefix)
    append('-')
    append(NSUUID().UUIDString)
}

/**
 * Sets [path]'s access and modification times to one hour in the past, for
 * tests that need to simulate an abandoned session without waiting a real
 * hour. Uses `utimes` directly (rather than [AppleFileAssetIo.touch], which
 * always sets "now") since a test is the only caller that needs an
 * arbitrary past timestamp.
 */
internal fun setModifiedTimeAnHourAgo(path: String) {
    memScoped {
        val times = allocArray<timeval>(2)
        val past = (time(null) - 3600).convert<Long>()
        times[0].tv_sec = past.convert()
        times[0].tv_usec = 0.convert()
        times[1].tv_sec = past.convert()
        times[1].tv_usec = 0.convert()
        utimes(path, times)
    }
}
