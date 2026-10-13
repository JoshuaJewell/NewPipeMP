package org.schabi.newpipe.database.playback.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.OffsetDateTime
import org.schabi.newpipe.database.stream.model.StreamEntity

/**
 * Entity tracking detailed playback statistics for streams.
 * Records actual playtime, skip events, completions, and other playback metrics.
 */
@Entity(
    tableName = PlaybackStatisticsEntity.TABLE_NAME,
    indices = [
        Index(value = [PlaybackStatisticsEntity.STREAM_ID])
    ],
    foreignKeys = [
        ForeignKey(
            entity = StreamEntity::class,
            parentColumns = [StreamEntity.STREAM_ID],
            childColumns = [PlaybackStatisticsEntity.STREAM_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ]
)
data class PlaybackStatisticsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = STATS_ID)
    var uid: Long = 0,

    @ColumnInfo(name = STREAM_ID)
    var streamId: Long,

    /**
     * Total actual playtime in milliseconds (cumulative across all plays).
     */
    @ColumnInfo(name = TOTAL_PLAY_TIME_MILLIS)
    var totalPlayTimeMillis: Long = 0,

    /**
     * Number of times the stream was skipped before completion.
     */
    @ColumnInfo(name = SKIP_COUNT)
    var skipCount: Int = 0,

    /**
     * Number of times the stream was played to completion (>90% of duration).
     */
    @ColumnInfo(name = COMPLETION_COUNT)
    var completionCount: Int = 0,

    /**
     * Number of times user seeked back to the beginning (restart).
     */
    @ColumnInfo(name = RESTART_COUNT)
    var restartCount: Int = 0,

    /**
     * Number of times volume was changed during playback of this stream.
     */
    @ColumnInfo(name = VOLUME_CHANGE_COUNT)
    var volumeChangeCount: Int = 0,

    /**
     * Number of times playback was paused.
     */
    @ColumnInfo(name = PAUSE_COUNT)
    var pauseCount: Int = 0,

    /**
     * Number of times playback was resumed/started.
     */
    @ColumnInfo(name = PLAY_COUNT)
    var playCount: Int = 0,

    /**
     * Number of times user seeked within the stream.
     */
    @ColumnInfo(name = SEEK_COUNT)
    var seekCount: Int = 0,

    /**
     * Timestamp of last update to these statistics.
     */
    @ColumnInfo(name = LAST_UPDATED)
    var lastUpdated: OffsetDateTime = OffsetDateTime.now(),

    // The columns below come from the on-device build's schema (version 18). This build
    // does not record them; they are declared so Room accepts that schema.
    @ColumnInfo(name = FORWARD_SEEK_COUNT)
    var forwardSeekCount: Int = 0,

    @ColumnInfo(name = BACKWARD_SEEK_COUNT)
    var backwardSeekCount: Int = 0,

    @ColumnInfo(name = VOLUME_UP_COUNT)
    var volumeUpCount: Int = 0,

    @ColumnInfo(name = VOLUME_DOWN_COUNT)
    var volumeDownCount: Int = 0,

    @ColumnInfo(name = PAUSE_RESUME_COUNT)
    var pauseResumeCount: Int = 0,

    @ColumnInfo(name = PAUSE_SKIP_COUNT)
    var pauseSkipCount: Int = 0,

    @ColumnInfo(name = DROPOUT_COUNT)
    var dropoutCount: Int = 0,

    @ColumnInfo(name = DROPOUT_MEAN)
    var dropoutMean: Double = 0.0,

    @ColumnInfo(name = DROPOUT_M2)
    var dropoutM2: Double = 0.0
) {
    companion object {
        const val TABLE_NAME = "playback_statistics"
        const val STATS_ID = "stats_id"
        const val STREAM_ID = "stream_id"
        const val TOTAL_PLAY_TIME_MILLIS = "total_play_time_millis"
        const val SKIP_COUNT = "skip_count"
        const val COMPLETION_COUNT = "completion_count"
        const val RESTART_COUNT = "restart_count"
        const val VOLUME_CHANGE_COUNT = "volume_change_count"
        const val PAUSE_COUNT = "pause_count"
        const val PLAY_COUNT = "play_count"
        const val SEEK_COUNT = "seek_count"
        const val LAST_UPDATED = "last_updated"
        const val FORWARD_SEEK_COUNT = "forward_seek_count"
        const val BACKWARD_SEEK_COUNT = "backward_seek_count"
        const val VOLUME_UP_COUNT = "volume_up_count"
        const val VOLUME_DOWN_COUNT = "volume_down_count"
        const val PAUSE_RESUME_COUNT = "pause_resume_count"
        const val PAUSE_SKIP_COUNT = "pause_skip_count"
        const val DROPOUT_COUNT = "dropout_count"
        const val DROPOUT_MEAN = "dropout_mean"
        const val DROPOUT_M2 = "dropout_m2"
    }
}
