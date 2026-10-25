/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetagTransactionTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    private var bytes = "original audio and tags".toByteArray()
    private fun input(uri: String) = ByteArrayInputStream(bytes)
    private fun output(uri: String): OutputStream {
        return object : ByteArrayOutputStream() {
            override fun close() {
                bytes = toByteArray()
            }
        }
    }

    @Test
    fun `successful edit is verified and recovery data is removed`() {
        val directory = temporary.newFolder()
        val transaction = RetagTransaction(directory)
        assertTrue(
            transaction.apply("target", "m4a", ::input, ::output) {
                it.writeText("edited")
                true
            }
        )
        assertEquals("edited", String(bytes))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun `already correct tags do not rewrite the original`() {
        val transaction = RetagTransaction(temporary.newFolder())
        var writes = 0
        assertFalse(
            transaction.apply("target", "m4a", ::input, { uri ->
                writes++
                output(uri)
            }) { false }
        )
        assertEquals(0, writes)
        assertEquals("original audio and tags", String(bytes))
    }

    @Test
    fun `tagging failure leaves original bytes untouched`() {
        val transaction = RetagTransaction(temporary.newFolder())
        try {
            transaction.apply("target", "m4a", ::input, ::output) { throw IOException("bad tags") }
            fail("Expected tagging failure")
        } catch (_: IOException) {
            assertEquals("original audio and tags", String(bytes))
        }
    }

    @Test
    fun `failed provider write restores original bytes`() {
        val transaction = RetagTransaction(temporary.newFolder())
        var writes = 0
        val failingOutput = { uri: String ->
            if (++writes == 1) {
                bytes = byteArrayOf()
                throw IOException("Provider failed after truncation")
            }
            output(uri)
        }
        try {
            transaction.apply("target", "m4a", ::input, failingOutput) {
                it.writeText("edited")
                true
            }
            fail("Expected provider failure")
        } catch (_: IOException) {
            assertEquals("original audio and tags", String(bytes))
        }
    }

    @Test
    fun `interrupted write is restored on next invocation`() {
        val directory = temporary.newFolder()
        File(directory, "original.bin").writeText("saved original")
        File(directory, "target").writeText("target")
        bytes = "partial output".toByteArray()
        assertTrue(RetagTransaction(directory).recover(::input, ::output))
        assertEquals("saved original", String(bytes))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun `failed recovery keeps journal and backup for another attempt`() {
        val directory = temporary.newFolder()
        File(directory, "original.bin").writeText("saved original")
        File(directory, "target").writeText("target")
        try {
            RetagTransaction(directory).recover(::input) { throw IOException("Permission lost") }
            fail("Expected recovery failure")
        } catch (_: RetagTransaction.RecoveryRequired) {
            assertTrue(File(directory, "target").exists())
            assertEquals("saved original", File(directory, "original.bin").readText())
        }
    }

    @Test
    fun `a completed transaction is not rolled back after a cleanup interruption`() {
        val directory = temporary.newFolder()
        File(directory, "original.bin").writeText("saved original")
        File(directory, "target").writeText("target")
        File(directory, "committed").writeText("written")
        bytes = "edited".toByteArray()
        assertFalse(RetagTransaction(directory).recover(::input, ::output))
        assertEquals("edited", String(bytes))
    }

    @Test
    fun `concurrent source change during staging is not overwritten`() {
        val transaction = RetagTransaction(temporary.newFolder())
        try {
            transaction.apply("target", "m4a", ::input, ::output) {
                it.writeText("edited")
                bytes = "changed by another app".toByteArray()
                true
            }
            fail("Expected conflict")
        } catch (_: IllegalStateException) {
            assertEquals("changed by another app", String(bytes))
        }
    }
}
