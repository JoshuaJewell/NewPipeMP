/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// A journal and byte-for-byte backup live in filesDir, not cacheDir. A provider may
// truncate its destination before a write fails. Do not discard the backup until
// the replacement or restoration has been read back and verified. One file at a time.
class RetagTransaction(private val directory: File) {
    class RecoveryRequired(message: String, cause: Throwable) : IOException(message, cause)

    private val original = File(directory, "original.bin")
    private val target = File(directory, "target")
    private val committed = File(directory, "committed")

    fun recover(openInput: (String) -> InputStream, openOutput: (String) -> OutputStream): Boolean {
        if (!target.exists()) {
            clean()
            return false
        }
        if (committed.exists()) {
            clean()
            return false
        }
        val uri = target.readText()
        try {
            check(original.isFile && original.length() > 0) { "Recovery backup missing" }
            original.inputStream().use { input -> openOutput(uri).use { input.copyTo(it) } }
            check(openInput(uri).use { digest(it) } == original.inputStream().use { digest(it) }) {
                "Restored file did not match its backup"
            }
            durableWrite(committed, "restored")
            clean()
            return true
        } catch (e: Exception) {
            throw RecoveryRequired("Could not restore $uri. Recovery copy retained at ${directory.path}", e)
        }
    }

    fun apply(
        uri: String,
        extension: String,
        openInput: (String) -> InputStream,
        openOutput: (String) -> OutputStream,
        edit: (File) -> Boolean
    ): Boolean {
        recover(openInput, openOutput)
        check(directory.exists() || directory.mkdirs()) { "Could not create recovery directory" }
        val staged = File(directory, "edited.$extension")
        try {
            openInput(uri).use { input ->
                FileOutputStream(original).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(original.length() > 0) { "Empty source file" }
            original.copyTo(staged, overwrite = true)
            if (!edit(staged)) {
                clean()
                return false
            }
            FileOutputStream(staged, true).use { it.fd.sync() }
            val before = original.inputStream().use { digest(it) }
            val after = staged.inputStream().use { digest(it) }
            check(openInput(uri).use { digest(it) } == before) { "File changed since preview; not overwriting it" }
            // Publish the journal only after the backup and edited copy are complete.
            durableWrite(target, uri)
            staged.inputStream().use { input -> openOutput(uri).use { input.copyTo(it) } }
            check(openInput(uri).use { digest(it) } == after) { "Written file verification failed" }
            durableWrite(committed, "written")
            clean()
            return true
        } catch (e: Exception) {
            if (target.exists() && !committed.exists()) {
                // If restoring also fails, recover throws and leaves the journal intact.
                recover(openInput, openOutput)
            } else {
                clean()
            }
            throw e
        }
    }

    private fun durableWrite(file: File, text: String) {
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not publish recovery journal" }
    }

    private fun clean() {
        // A completed marker must outlive the journal during cleanup, including a crash.
        if (target.exists()) check(target.delete()) { "Could not clear recovery journal" }
        directory.listFiles()?.forEach { check(it.delete()) { "Could not remove staging file" } }
    }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) hash.update(buffer, 0, count)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
