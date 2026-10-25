/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.download.library.LibraryRetagPlanner

object LibraryRetagDialog {
    private const val EXCLUDED = "library_retag_excluded_playlists"

    @JvmStatic
    fun show(context: Context, disposables: CompositeDisposable) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_title)
            .setMessage(R.string.library_retag_warning)
            .setPositiveButton(R.string.library_retag_continue) { _, _ -> prepare(context, disposables) }
            .setNegativeButton(R.string.cancel, null)
        val previous = LibraryRetagJob.reportFile(context)
        if (previous.exists()) {
            dialog.setNeutralButton(R.string.library_retag_last_report) { _, _ -> message(context, previous.readText()) }
        }
        dialog.show()
    }

    private fun prepare(context: Context, disposables: CompositeDisposable) {
        if (LibraryDownloadJob.isRunning() || LibraryRetagJob.isRunning()) {
            message(context, context.getString(R.string.library_retag_busy))
            return
        }
        val progress = AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_title)
            .setMessage(R.string.library_retag_collecting)
            .setCancelable(false)
            .show()
        val app = context.applicationContext
        disposables.add(
            Single.fromCallable { LibraryRetagJob.recover(app) }
                .subscribeOn(Schedulers.io())
                .flatMap { LibraryDownloadJob.prepareRetag(app) }
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally { progress.dismiss() }
                .subscribe(
                    { prepared ->
                        if (prepared.unloadedPlaylists.isNotEmpty()) {
                            message(context, context.getString(R.string.library_retag_unloaded) + "\n" + prepared.unloadedPlaylists.joinToString("\n"))
                        } else {
                            selectExclusions(context, prepared, disposables)
                        }
                    },
                    { error -> message(context, error.message ?: error.javaClass.simpleName) }
                )
        )
    }

    private fun selectExclusions(context: Context, prepared: LibraryDownloadJob.Prepared, disposables: CompositeDisposable) {
        val playlists = prepared.plan.playlists
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getStringSet(EXCLUDED, emptySet()).orEmpty()
        val excluded = BooleanArray(playlists.size) { playlists[it].fileName in previous }
        val labels = playlists.map { "${it.name} (${it.keys.size})" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_exclude)
            .setMultiChoiceItems(labels, excluded) { _, which, checked -> excluded[which] = checked }
            .setPositiveButton(R.string.library_retag_preview) { _, _ ->
                val selected = playlists.indices.filter { excluded[it] }.mapTo(HashSet()) { playlists[it].fileName }
                prefs.edit().putStringSet(EXCLUDED, selected).apply()
                val plan = LibraryRetagPlanner.plan(playlists, prepared.plan.tracks, prepared.existing, selected)
                preview(context, plan, disposables)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun preview(context: Context, plan: LibraryRetagPlanner.Plan, disposables: CompositeDisposable) {
        val report = buildString {
            append(context.getString(R.string.library_retag_warning)).append("\n\n")
            append("${plan.changes.size} proposed files; ${plan.skipped.size} skipped.\n")
            for (change in plan.changes) {
                append("\n${change.track.title} — ${change.track.artist}\n")
                append("Album: ${change.album}; track: ${change.number}\n${change.uri}\n")
            }
            if (plan.skipped.isNotEmpty()) append("\nSkipped (unchanged):\n")
            for (skip in plan.skipped) append("${skip.track.title}: ${skip.reason}\n")
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_preview)
            .setMessage(report)
            .setNeutralButton(R.string.library_download_copy_report) { _, _ -> copy(context, report) }
            .setNegativeButton(R.string.cancel, null)
        if (plan.changes.isNotEmpty()) {
            dialog.setPositiveButton(R.string.library_retag_apply) { _, _ -> apply(context, plan, disposables) }
        }
        dialog.show()
    }

    private fun apply(context: Context, plan: LibraryRetagPlanner.Plan, disposables: CompositeDisposable) {
        if (LibraryDownloadJob.isRunning() || LibraryRetagJob.isRunning()) {
            message(context, context.getString(R.string.library_retag_busy))
            return
        }
        val progress = AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_title)
            .setMessage(R.string.library_retag_working)
            .setCancelable(false)
            .show()
        disposables.add(
            LibraryRetagJob.run(context, plan)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally { progress.dismiss() }
                .subscribe(
                    { report -> message(context, report) },
                    { error -> message(context, error.message ?: error.javaClass.simpleName) }
                )
        )
    }

    private fun message(context: Context, text: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.library_retag_title)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.library_download_copy_report) { _, _ -> copy(context, text) }
            .show()
    }

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.library_retag_title), text))
    }
}
