/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import android.content.Context
import org.schabi.newpipe.NewPipeDatabase

// Process-wide singleton holder. The codebase has no DI framework, so this matches the
// existing pattern (see NewPipeDatabase) of double-checked Kotlin singletons.
object AdaptiveShuffleHolder {
    @Volatile private var integration: AdaptivePlayerIntegration? = null

    fun get(context: Context): AdaptivePlayerIntegration {
        val existing = integration
        if (existing != null) return existing
        synchronized(this) {
            val again = integration
            if (again != null) return again
            val db = NewPipeDatabase.getInstance(context.applicationContext)
            val repository = AdaptiveShuffleRepository(db)
            val engine = AdaptiveShuffleEngine(repository)
            val init = engine.initialise()
            if (init is AdaptiveShuffleEngine.InitialiseResult.SchemaMismatch) {
                engine.rebuildFromPending()
            } else if (init is AdaptiveShuffleEngine.InitialiseResult.Fresh) {
                // No persisted model and no resolved events yet; prime from aggregate stats.
                Bootstrap.runIfNeeded(db, engine, repository)
            }
            val materialiser = CandidateMaterialiser(repository)
            val telemetry = TelemetryLog.get(context.applicationContext)
            val created = AdaptivePlayerIntegration(engine, materialiser, repository, telemetry)
            integration = created
            return created
        }
    }
}
