/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util.stall

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.adaptive.AdaptiveShuffleHolder

// Watches for the app wedging: every few seconds it asks each part that has been seen to
// stall (the database's connections and write lock, Room's query threads, the main
// thread, the Rx IO pool, the adaptive shuffle thread) to answer something trivial. If
// one has not answered after STALL_AFTER_MILLIS of the app running, it saves the stack of
// every thread to a file, once, and again a minute later if still stuck, and posts a
// notification. Debug settings > Stall reports saves them for sharing.
//
// It only observes: a probe that is stuck stays stuck on its own thread and is not
// retried until it answers. Time the process spends suspended (Doze, the cached-app
// freezer) is not counted as waiting; see StallDetector.
object StallWatchdog {
    private const val TAG = "StallWatchdog"
    private const val TICK_MILLIS = 2_000L
    private const val PROBE_EVERY_TICKS = 5
    private const val STALL_AFTER_MILLIS = 15_000L
    private const val REDUMP_AFTER_MILLIS = 60_000L
    private const val MAX_REPORTS = 10
    private const val MAX_EVENTS = 100
    private const val NOTIFICATION_ID = 0x5741

    const val DIRECTORY = "stall-reports"

    private val detector = StallDetector(TICK_MILLIS, STALL_AFTER_MILLIS, REDUMP_AFTER_MILLIS)
    private val events = ArrayDeque<String>()
    private var started = false
    private var currentReport: File? = null

    // One thread per probe, so a probe that hangs blocks only its own next run.
    private val probeThreads = HashMap<String, ExecutorService>()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        note("process started")
        Thread({ loop(app) }, "StallWatchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    // SimpleDateFormat is not thread-safe, and probes note events from their own threads.
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    fun reportDirectory(context: Context): File = File(context.filesDir, DIRECTORY)

    // Newest first.
    fun reports(context: Context): List<File> = reportDirectory(context).listFiles { f -> f.name.endsWith(".txt") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun loop(context: Context) {
        var ticks = 0
        while (true) {
            try {
                Thread.sleep(TICK_MILLIS)
                if (ticks++ % PROBE_EVERY_TICKS == 0) probeAll(context)
                val now = SystemClock.uptimeMillis()
                val due = synchronized(detector) { detector.tick(now) }
                for (event in due) handle(context, event)
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                // Never let the watchdog itself take the app down.
                Log.w(TAG, "watchdog tick failed", t)
            }
        }
    }

    private fun probeAll(context: Context) {
        probe("database read") {
            NewPipeDatabase.getInstance(context).openHelper.readableDatabase.query("SELECT 1").use { it.moveToFirst() }
        }
        probe("database write lock") {
            val db = NewPipeDatabase.getInstance(context).openHelper.writableDatabase
            db.beginTransactionNonExclusive()
            db.endTransaction()
        }
        hop("Room query thread") { done -> NewPipeDatabase.getInstance(context).queryExecutor.execute(done) }
        hop("main thread") { done -> Handler(Looper.getMainLooper()).post(done) }
        hop("Rx IO pool") { done -> Schedulers.io().scheduleDirect(done) }
        hop("adaptive shuffle thread") { done -> AdaptiveShuffleHolder.SCHEDULER.scheduleDirect(done) }
    }

    // Runs work on the probe's own thread.
    private fun probe(name: String, work: () -> Unit) {
        if (!begin(name)) return
        executorFor(name).execute {
            try {
                work()
            } catch (t: Throwable) {
                note("$name probe failed: $t")
            } finally {
                end(name)
            }
        }
    }

    // Asks another thread or pool to run a no-op; answered when it runs.
    private fun hop(name: String, submit: (Runnable) -> Unit) {
        if (!begin(name)) return
        try {
            submit(Runnable { end(name) })
        } catch (t: Throwable) {
            note("$name could not be reached: $t")
            end(name)
        }
    }

    private fun begin(name: String): Boolean = synchronized(detector) {
        if (name in detector.outstanding) return false
        detector.started(name)
        true
    }

    private fun end(name: String) {
        synchronized(detector) { detector.finished(name) }
    }

    private fun executorFor(name: String): ExecutorService = synchronized(probeThreads) {
        probeThreads.getOrPut(name) {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "StallWatchdog probe: $name").apply { isDaemon = true }
            }
        }
    }

    private fun handle(context: Context, event: StallDetector.Event) {
        when (event) {
            is StallDetector.Event.Suspended -> note("process was not running for ${event.gapMillis / 1000} s")

            is StallDetector.Event.Stalled -> {
                val stuck = event.probes.entries.joinToString { "${it.key} (${it.value / 1000} s)" }
                note((if (event.repeat) "still stuck: " else "stalled: ") + stuck)
                writeReport(context, event)
                if (!event.repeat) notify(context, stuck)
            }

            is StallDetector.Event.Recovered -> {
                note("recovered after ${event.waitedMillis / 1000} s")
                currentReport?.let { f ->
                    runCatching { f.appendText("\n${timestamp()}  recovered after ${event.waitedMillis / 1000} s\n") }
                }
                currentReport = null
            }
        }
    }

    // A line for the recent-events log in every report: what the player did around a
    // failure, for example. Cheap; safe from any thread.
    @JvmStatic
    fun record(message: String) = note(message)

    // Saves a report now, whatever the probes say: for when something is wrong that the
    // probes do not catch. Returns the file, or null if it could not be written.
    fun dumpNow(context: Context): File? = try {
        val dir = reportDirectory(context).apply { mkdirs() }
        val file = File(dir, "manual-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
        val waiting = synchronized(detector) { detector.waitingNow() }
        file.writeText(buildReport("NewPipe thread dump (saved by hand)", waiting, header = true))
        reports(context).drop(MAX_REPORTS).forEach { it.delete() }
        file
    } catch (t: Throwable) {
        Log.w(TAG, "could not write thread dump", t)
        null
    }

    @Synchronized
    private fun note(message: String) {
        events.addLast("${timestamp()}  $message")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun writeReport(context: Context, event: StallDetector.Event.Stalled) {
        try {
            val dir = reportDirectory(context).apply { mkdirs() }
            val file = if (event.repeat && currentReport != null) {
                currentReport!!
            } else {
                File(dir, "stall-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
            }
            currentReport = file
            file.appendText(
                buildReport(
                    if (event.repeat) "Still stuck" else "NewPipe stall report",
                    event.probes,
                    header = !event.repeat
                )
            )
            reports(context).drop(MAX_REPORTS).forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "could not write stall report", t)
        }
    }

    private fun buildReport(title: String, probes: Map<String, Long>, header: Boolean): String = buildString {
        append(if (header) "===== " else "\n\n===== ").append(title).append(' ')
        append(timestamp()).append(" =====\n")
        if (header) {
            append("version ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("uptime ").append(SystemClock.uptimeMillis() / 1000).append(" s awake, ")
            append(SystemClock.elapsedRealtime() / 1000).append(" s since boot\n")
        }
        append("\nNot answering:\n")
        if (probes.isEmpty()) append("  (nothing: every probe has answered)\n")
        for ((name, waited) in probes) append("  ").append(name).append(": ").append(waited / 1000).append(" s\n")
        append("\nRecent events:\n")
        synchronized(this@StallWatchdog) { events.forEach { append("  ").append(it).append('\n') } }
        append("\nThreads:\n")
        val all = Thread.getAllStackTraces().entries.sortedWith(compareBy({ it.key.state != Thread.State.BLOCKED }, { it.key.name }))
        for ((thread, frames) in all) {
            append("\n\"").append(thread.name).append("\" ").append(thread.state)
            if (thread.isDaemon) append(" daemon")
            append(" prio=").append(thread.priority).append('\n')
            for (frame in frames) append("    at ").append(frame).append('\n')
        }
    }

    private fun notify(context: Context, stuck: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = NotificationCompat.Builder(context, context.getString(R.string.error_report_channel_id))
                .setSmallIcon(R.drawable.ic_bug_report)
                .setContentTitle(context.getString(R.string.stall_notification_title))
                .setContentText(context.getString(R.string.stall_notification_text, stuck))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.stall_notification_text, stuck)))
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "could not post stall notification", t)
        }
    }
}
