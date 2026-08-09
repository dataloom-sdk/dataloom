package io.dataloom.storage.file.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JVM/Android actual implementation of [FileSystemFacade].
 *
 * Uses `java.io.File` and `java.nio.file.Files.move` for atomic rename.
 * All writes use the write-to-temp-then-rename idiom.
 */
internal actual class FileSystemFacade actual constructor(actual val baseDir: String) {

    private val base = File(baseDir)

    actual fun ensureInitialized() {
        try {
            mkdirsSafe(base)
            mkdirsSafe(File(base, "outbound"))
            mkdirsSafe(File(base, "rejected"))
            mkdirsSafe(File(base, "inbound"))
            mkdirsSafe(File(base, "checkpoint"))
        } catch (e: Exception) {
            throw FileStorageIoException("Directory initialization failed.")
        }
    }

    actual fun baseDirExists(): Boolean = base.exists() && base.isDirectory

    actual fun readTextFile(relativePath: String): String? {
        val file = File(base, relativePath)
        return try {
            if (!file.exists()) null else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw FileStorageIoException("Read failed for path: ${file.name}")
        }
    }

    actual fun writeTextFileAtomically(relativePath: String, content: String) {
        val target = File(base, relativePath)
        val tmp = File(target.parent, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw FileStorageIoException("Atomic write failed for path: ${target.name}")
        }
    }

    actual fun deleteFile(relativePath: String) {
        val file = File(base, relativePath)
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            throw FileStorageIoException("Delete failed for path: ${file.name}")
        }
    }

    actual fun moveFileAtomically(fromRelative: String, toRelative: String) {
        val src = File(base, fromRelative)
        val dst = File(base, toRelative)
        try {
            if (!src.exists()) return
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw FileStorageIoException("Move failed from ${src.name} to ${dst.name}")
        }
    }

    actual fun listFileNames(subdirectory: String): List<String> {
        val dir = File(base, subdirectory)
        return try {
            if (!dir.exists() || !dir.isDirectory) emptyList()
            else dir.listFiles()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            throw FileStorageIoException("List failed for subdirectory: ${dir.name}")
        }
    }

    private fun mkdirsSafe(dir: File) {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw FileStorageIoException("Could not create directory: ${dir.name}")
        }
    }
}
