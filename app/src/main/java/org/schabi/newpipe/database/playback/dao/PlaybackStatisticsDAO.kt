package org.schabi.newpipe.database.playback.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import java.time.OffsetDateTime
import org.schabi.newpipe.database.playback.model.PlaybackStatisticsEntity
import org.schabi.newpipe.player.playback.LeavePointStats

@Dao
abstract class PlaybackStatisticsDAO {

    @Query("SELECT * FROM playback_statistics WHERE stream_id = :streamId")
    abstract fun getStatistics(streamId: Long): Maybe<PlaybackStatisticsEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract fun silentInsert(stats: PlaybackStatisticsEntity): Long

    @Query(
        """
        UPDATE playback_statistics SET
            total_play_time_millis = :totalPlayTimeMillis,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun updatePlayTime(
        streamId: Long,
        totalPlayTimeMillis: Long,
        lastUpdated: OffsetDateTime
    ): Int

    @Query(
        """
        UPDATE playback_statistics SET
            skip_count = skip_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementSkipCount(streamId: Long, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            completion_count = completion_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementCompletionCount(
        streamId: Long,
        lastUpdated: OffsetDateTime
    ): Int

    @Query(
        """
        UPDATE playback_statistics SET
            restart_count = restart_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementRestartCount(streamId: Long, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            volume_change_count = volume_change_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementVolumeChangeCount(
        streamId: Long,
        lastUpdated: OffsetDateTime
    ): Int

    @Query(
        """
        UPDATE playback_statistics SET
            pause_count = pause_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementPauseCount(streamId: Long, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            play_count = play_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementPlayCount(streamId: Long, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            seek_count = seek_count + 1,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun incrementSeekCount(streamId: Long, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            forward_seek_count = forward_seek_count + :forward,
            backward_seek_count = backward_seek_count + :backward,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun addSeekDirection(
        streamId: Long,
        forward: Int,
        backward: Int,
        lastUpdated: OffsetDateTime
    ): Int

    @Query(
        """
        UPDATE playback_statistics SET
            volume_up_count = volume_up_count + :up,
            volume_down_count = volume_down_count + :down,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun addVolumeStep(streamId: Long, up: Int, down: Int, lastUpdated: OffsetDateTime): Int

    @Query(
        """
        UPDATE playback_statistics SET
            pause_resume_count = pause_resume_count + :resumed,
            pause_skip_count = pause_skip_count + :skipped,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun addPauseOutcome(
        streamId: Long,
        resumed: Int,
        skipped: Int,
        lastUpdated: OffsetDateTime
    ): Int

    @Query(
        """
        UPDATE playback_statistics SET
            dropout_count = :count,
            dropout_mean = :mean,
            dropout_m2 = :m2,
            last_updated = :lastUpdated
        WHERE stream_id = :streamId
        """
    )
    internal abstract fun setLeavePointStats(
        streamId: Long,
        count: Int,
        mean: Double,
        m2: Double,
        lastUpdated: OffsetDateTime
    ): Int

    @Query("SELECT SUM(total_play_time_millis) FROM playback_statistics")
    abstract fun getTotalPlayTime(): Flowable<Long>

    @Query(
        """
        SELECT ps.*, s.title, s.uploader
        FROM playback_statistics ps
        INNER JOIN streams s ON ps.stream_id = s.uid
        WHERE s.user_rating IS NULL
        ORDER BY ps.total_play_time_millis DESC
        LIMIT :limit
        """
    )
    abstract fun getMostPlayedUnratedStreams(limit: Int): Flowable<List<PlaybackStatsWithStream>>

    @Query(
        """
        SELECT ps.*, s.title, s.uploader
        FROM playback_statistics ps
        INNER JOIN streams s ON ps.stream_id = s.uid
        WHERE ps.skip_count > 0
        ORDER BY ps.skip_count DESC
        LIMIT :limit
        """
    )
    abstract fun getMostSkippedStreams(limit: Int): Flowable<List<PlaybackStatsWithStream>>

    @Query(
        """
        SELECT ps.*, s.title, s.uploader
        FROM playback_statistics ps
        INNER JOIN streams s ON ps.stream_id = s.uid
        WHERE ps.completion_count > 0
        ORDER BY ps.completion_count DESC
        LIMIT :limit
        """
    )
    abstract fun getMostCompletedStreams(limit: Int): Flowable<List<PlaybackStatsWithStream>>

    @Query(
        """
        SELECT ps.*, s.title, s.uploader
        FROM playback_statistics ps
        INNER JOIN streams s ON ps.stream_id = s.uid
        WHERE ps.restart_count > 0
        ORDER BY ps.restart_count DESC
        LIMIT :limit
        """
    )
    abstract fun getMostRestartedStreams(limit: Int): Flowable<List<PlaybackStatsWithStream>>

    @Query("SELECT COUNT(*) FROM playback_statistics WHERE skip_count > 0 OR completion_count > 0")
    abstract fun getTotalTracksPlayed(): Flowable<Long>

    /**
     * Adds playtime to a stream's statistics.
     * Creates entry if it doesn't exist.
     */
    @Transaction
    open fun addPlayTime(streamId: Long, playTimeMillis: Long) {
        val now = OffsetDateTime.now()
        val existing = getStatistics(streamId).blockingGet()

        if (existing == null) {
            // Create new entry
            val stats = PlaybackStatisticsEntity(
                streamId = streamId,
                totalPlayTimeMillis = playTimeMillis,
                lastUpdated = now
            )
            silentInsert(stats)
        } else {
            // Update existing
            updatePlayTime(streamId, existing.totalPlayTimeMillis + playTimeMillis, now)
        }
    }

    /**
     * Records a skip event for a stream.
     */
    @Transaction
    open fun recordSkip(streamId: Long) {
        ensureStatsExist(streamId)
        incrementSkipCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a completion event for a stream.
     */
    @Transaction
    open fun recordCompletion(streamId: Long) {
        ensureStatsExist(streamId)
        incrementCompletionCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a restart event for a stream.
     */
    @Transaction
    open fun recordRestart(streamId: Long) {
        ensureStatsExist(streamId)
        incrementRestartCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a volume change event for a stream.
     */
    @Transaction
    open fun recordVolumeChange(streamId: Long) {
        ensureStatsExist(streamId)
        incrementVolumeChangeCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a pause event for a stream.
     */
    @Transaction
    open fun recordPause(streamId: Long) {
        ensureStatsExist(streamId)
        incrementPauseCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a play/resume event for a stream.
     */
    @Transaction
    open fun recordPlay(streamId: Long) {
        ensureStatsExist(streamId)
        incrementPlayCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a seek event for a stream.
     */
    @Transaction
    open fun recordSeek(streamId: Long) {
        ensureStatsExist(streamId)
        incrementSeekCount(streamId, OffsetDateTime.now())
    }

    /**
     * Records a seek within a stream, with its direction.
     */
    @Transaction
    open fun recordSeek(streamId: Long, forward: Boolean) {
        ensureStatsExist(streamId)
        val now = OffsetDateTime.now()
        incrementSeekCount(streamId, now)
        addSeekDirection(streamId, if (forward) 1 else 0, if (forward) 0 else 1, now)
    }

    /**
     * Records one step of the media volume, up or down, while the stream was current.
     */
    @Transaction
    open fun recordVolumeStep(streamId: Long, up: Boolean) {
        ensureStatsExist(streamId)
        addVolumeStep(streamId, if (up) 1 else 0, if (up) 0 else 1, OffsetDateTime.now())
    }

    /**
     * Records how a pause ended: playback resumed, or the stream was left while paused.
     */
    @Transaction
    open fun recordPauseOutcome(streamId: Long, resumed: Boolean) {
        ensureStatsExist(streamId)
        addPauseOutcome(streamId, if (resumed) 1 else 0, if (resumed) 0 else 1, OffsetDateTime.now())
    }

    /**
     * Adds the point (0 to 1 of the duration) at which a listen of the stream ended to the
     * stream's running mean and spread of leave points.
     */
    @Transaction
    open fun recordLeavePoint(streamId: Long, fraction: Double) {
        ensureStatsExist(streamId)
        val stats = getStatistics(streamId).blockingGet() ?: return
        val next = LeavePointStats.add(
            LeavePointStats(stats.dropoutCount, stats.dropoutMean, stats.dropoutM2),
            fraction
        )
        setLeavePointStats(streamId, next.count, next.mean, next.m2, OffsetDateTime.now())
    }

    private fun ensureStatsExist(streamId: Long) {
        val existing = getStatistics(streamId).blockingGet()
        if (existing == null) {
            silentInsert(
                PlaybackStatisticsEntity(
                    streamId = streamId,
                    lastUpdated = OffsetDateTime.now()
                )
            )
        }
    }

    /**
     * Data class combining playback stats with stream info.
     */
    data class PlaybackStatsWithStream(
        val stats_id: Long,
        val stream_id: Long,
        val total_play_time_millis: Long,
        val skip_count: Int,
        val completion_count: Int,
        val restart_count: Int,
        val volume_change_count: Int,
        val title: String,
        val uploader: String?
    )
}
