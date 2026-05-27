package org.schabi.newpipe.util.rating

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import org.schabi.newpipe.NewPipeDatabase

/**
 * One-time migration that walks every offline-mapped audio file and copies its
 * in-file rating tag (if any) into the [org.schabi.newpipe.database.stream.dao.StreamDAO]
 * cache. Runs exactly once per install, guarded by a SharedPreferences flag.
 *
 * Exists because pre-acetate NewPipeMP installs have ratings only in
 * `streams.user_rating`, while post-acetate the file is the source of truth.
 * The first run after the migration lands ensures any rating already in a
 * downloaded file's tag (e.g. set by Acetate while the app was uninstalled)
 * is reflected in the Room cache.
 */
object RatingBackfillJob {

    private const val TAG = "RatingBackfillJob"
    private const val PREF_KEY = "rating_backfill_done_v1"

    /**
     * Pure compute: given (streamId, file) pairs, return (streamId, stars)
     * updates for the files that carry a rating tag. Files that don't exist
     * or aren't rated are filtered out. Used by the runner; isolated for
     * testing.
     */
    @JvmStatic
    fun compute(input: List<Pair<Long, File>>): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        for ((streamId, file) in input) {
            if (!file.isFile) continue
            val rating = RatingTagReader.read(file) ?: continue
            out += streamId to rating
        }
        return out
    }

    /**
     * Public entry. Idempotent: returns Completable.complete() after the first
     * successful run. Safe to call from Application.onCreate (subscribes on
     * Schedulers.io()).
     */
    @JvmStatic
    fun runOnce(context: Context): Completable {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_KEY, false)) return Completable.complete()

        return Completable.fromAction {
            val db = NewPipeDatabase.getInstance(context)
            val mappingDao = db.offlineFileMappingDAO()
            val streamDao = db.streamDAO()

            val pairs = mappingDao.getAllBlocking().mapNotNull { mapping ->
                val uri = Uri.parse(mapping.localFileUri)
                if (uri.scheme != "file" || uri.path == null) return@mapNotNull null
                val file = File(uri.path!!)
                // Resolve streamId from (service_id, url) via the same DAO method
                // HistoryRecordManager uses.
                val streamRows = streamDao
                    .getStream(mapping.serviceId.toLong(), mapping.streamUrl)
                    .blockingFirst()
                val sid = streamRows.firstOrNull()?.uid ?: return@mapNotNull null
                sid to file
            }

            val updates = compute(pairs)
            db.runInTransaction {
                for ((sid, stars) in updates) {
                    streamDao.updateRating(sid, stars)
                }
            }
            prefs.edit().putBoolean(PREF_KEY, true).apply()
            Log.i(TAG, "Backfilled ${updates.size} ratings from file tags")
        }.subscribeOn(Schedulers.io())
    }
}
