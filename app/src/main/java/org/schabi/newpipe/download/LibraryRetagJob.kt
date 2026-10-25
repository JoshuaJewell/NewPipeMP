/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.schabi.newpipe.download.library.LibraryRetagPlanner
import org.schabi.newpipe.download.library.RetagTransaction
import org.schabi.newpipe.util.rating.AlbumTagEditor
import org.schabi.newpipe.util.rating.AudioContainer

object LibraryRetagJob {
    private val running = AtomicBoolean(false)

    @JvmStatic
    fun isRunning(): Boolean = running.get()

    private fun transaction(context: Context): RetagTransaction {
        return RetagTransaction(File(context.filesDir, "library-retag-recovery"))
    }

    // An interrupted provider write is restored before planning another retag operation.
    fun recover(context: Context): Boolean {
        check(!LibraryDownloadJob.isRunning()) { "Pause Download library before recovery" }
        check(running.compareAndSet(false, true)) { "Retagging is already running" }
        try {
            return transaction(context).recover(
                { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot read $uri") },
                { uri -> context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Cannot write $uri") }
            )
        } finally {
            running.set(false)
        }
    }

    fun run(context: Context, plan: LibraryRetagPlanner.Plan): Single<String> {
        check(!LibraryDownloadJob.isRunning()) { "Pause Download library before retagging" }
        check(running.compareAndSet(false, true)) { "Retagging is already running" }
        val app = context.applicationContext
        // cache keeps the single subscribed job alive if the settings screen rotates;
        // it must not release its guard while a provider write is still in progress.
        return Single.fromCallable {
            try {
                reportFile(app).writeText("Retagging started. If interrupted, open Retag existing library to check recovery before continuing.")
                val report = execute(app, plan)
                reportFile(app).writeText(report)
                report
            } catch (e: Exception) {
                reportFile(app).writeText("Retagging stopped: ${e.message}")
                throw e
            } finally {
                running.set(false)
            }
        }.subscribeOn(Schedulers.io()).cache()
    }

    fun reportFile(context: Context): File {
        return File(context.filesDir, "library-retag-last-report.txt")
    }

    private fun execute(context: Context, plan: LibraryRetagPlanner.Plan): String {
        val transaction = transaction(context)
        val openInput = { uri: String ->
            context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot read $uri")
        }
        val openOutput = { uri: String ->
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Cannot write $uri")
        }
        transaction.recover(openInput, openOutput)
        var changed = 0
        var unchanged = 0
        val problems = ArrayList<String>()
        for (change in plan.changes) {
            val uri = Uri.parse(change.uri)
            try {
                val name = if (uri.scheme == "file") {
                    File(uri.path ?: error("Missing path")).name
                } else {
                    DocumentFile.fromSingleUri(context, uri)?.name ?: error("Missing document name")
                }
                val container = AudioContainer.fromExtension(name.substringAfterLast('.', ""))
                    ?: error("Unsupported container: $name (only MP3, M4A, FLAC and Opus are supported)")
                val file = if (uri.scheme == "file") File(uri.path!!) else null
                val mtime = file?.lastModified()
                val wrote = transaction.apply(change.uri, container.extension, openInput, openOutput) { staged ->
                    if (AlbumTagEditor.read(staged) == (change.album to change.number.toString())) {
                        false
                    } else {
                        AlbumTagEditor.write(staged, change.album, change.number)
                        true
                    }
                }
                if (wrote) {
                    changed++
                    if (mtime != null) file?.setLastModified(mtime)
                    if (file != null) MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                } else {
                    unchanged++
                }
            } catch (e: RetagTransaction.RecoveryRequired) {
                // Never move on to another track while an original needs recovery.
                throw e
            } catch (e: Exception) {
                problems += "${change.track.title}: ${e.message}"
            }
        }
        return buildString {
            append("$changed files retagged; $unchanged already correct; ${problems.size} failed; ${plan.skipped.size} skipped.\n")
            append("No audio was downloaded. Refresh VLC's media library to see updated album tags.\n")
            if (problems.isNotEmpty()) append("\nFailures:\n").append(problems.joinToString("\n"))
            if (plan.skipped.isNotEmpty()) {
                append("\nSkipped:\n")
                for (skip in plan.skipped) append("${skip.track.title}: ${skip.reason}\n")
            }
        }
    }
}
