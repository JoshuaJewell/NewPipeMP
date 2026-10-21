/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import android.content.Context
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Executors
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R

// Process-wide singleton holder. The codebase has no DI framework, so this matches the
// existing pattern (see NewPipeDatabase) of double-checked Kotlin singletons.
object AdaptiveShuffleHolder {
    @Volatile private var integration: AdaptivePlayerIntegration? = null

    // All adaptive work runs here, one task at a time and in submission order, so a
    // track's resolution always lands before the pick that follows it. Spreading it over
    // Schedulers.io, as before, let the two race.
    @JvmField
    val SCHEDULER: Scheduler = Schedulers.from(
        Executors.newSingleThreadExecutor { r -> Thread(r, "adaptive-shuffle").apply { isDaemon = true } }
    )

    fun get(context: Context): AdaptivePlayerIntegration {
        val existing = integration
        if (existing != null) return existing
        synchronized(this) {
            val again = integration
            if (again != null) return again
            val db = NewPipeDatabase.getInstance(context.applicationContext)
            val repository = AdaptiveShuffleRepository(db)
            val engine = AdaptiveShuffleEngine(repository)
            val materialiser = CandidateMaterialiser(repository)
            val telemetry = TelemetryLog.get(context.applicationContext)
            val appContext = context.applicationContext
            val thompsonKey = appContext.getString(R.string.adaptive_shuffle_thompson_key)
            val selectionMode = {
                val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
                if (prefs.getBoolean(thompsonKey, false)) SelectionMode.THOMPSON else SelectionMode.UCB
            }
            val baselineKey = appContext.getString(R.string.adaptive_shuffle_baseline_share_key)
            val baselineDefault = appContext.getString(R.string.adaptive_shuffle_baseline_share_default)
            val baselineShare = {
                val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
                val percent = prefs.getString(baselineKey, baselineDefault)?.toIntOrNull() ?: 0
                percent.coerceIn(0, 100) / 100.0
            }
            val conditions = ListeningConditionsReader(appContext)
            val created = AdaptivePlayerIntegration(
                engine,
                materialiser,
                repository,
                telemetry,
                selectionMode,
                conditions = conditions::read,
                baselineShare = baselineShare
            )
            val init = engine.initialise()
            if (init is AdaptiveShuffleEngine.InitialiseResult.SchemaMismatch) {
                // Schema changed: replay history under the new features and reward, using
                // the telemetry log to recover outcomes older events did not store.
                engine.rebuildFromPending(created.telemetryIndex())
            } else if (init is AdaptiveShuffleEngine.InitialiseResult.Fresh) {
                // No persisted model and no resolved events yet; prime from aggregate stats.
                Bootstrap.runIfNeeded(db, engine, repository)
            }
            integration = created
            return created
        }
    }
}
