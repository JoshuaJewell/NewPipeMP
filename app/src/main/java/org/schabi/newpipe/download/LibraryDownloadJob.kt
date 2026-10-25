/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.download.library.LibraryFileMatcher
import org.schabi.newpipe.download.library.LibraryPlan
import org.schabi.newpipe.download.library.LibraryPlanner
import org.schabi.newpipe.download.library.LibraryPlaylist
import org.schabi.newpipe.download.library.LibraryTrack
import org.schabi.newpipe.download.library.LocalAudioFile
import org.schabi.newpipe.download.library.M3u
import org.schabi.newpipe.download.library.M3uEntry
import org.schabi.newpipe.download.library.PlannedPlaylist
import org.schabi.newpipe.download.library.PlannedTrack
import org.schabi.newpipe.download.library.RelativePath
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.streams.io.StoredDirectoryHelper
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ListHelper
import org.schabi.newpipe.util.PlaylistFetcher
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.MissionRecoveryInfo
import us.shandian.giga.postprocessing.Postprocessing
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.service.MissionState

// Downloads every playlist in the library, local and bookmarked, as audio: each distinct
// track once, into one "Library" folder in the audio download folder, then one .m3u8
// playlist file per playlist next to them.
//
// The job is resumable. Its state is saved to a file as it goes, a few downloads are in
// flight at a time (so stream URLs are fresh when a download starts, and the download
// list is not flooded), and each track is retried with a fresh extraction before it is
// given up on. Repeated network failures pause the job instead of failing the rest of
// the library. All state is touched on the main thread.
object LibraryDownloadJob {
    private const val TAG = "LibraryDownload"
    private const val STATE_FILE = "library_download.state"
    const val LIBRARY_FOLDER = "Library"
    private const val IN_FLIGHT = 3
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MILLIS = 15_000L
    private const val PAUSE_AFTER_NETWORK_FAILURES = 3
    private const val SAVE_DELAY_MILLIS = 2_000L
    private const val NOTIFICATION_ID = 0x4c49_4200

    enum class Status { PENDING, ACTIVE, DONE, FAILED }

    class Item(val planned: PlannedTrack) : Serializable {
        var status = Status.PENDING
        var attempts = 0
        var error: String? = null
        var fileName: String? = null
        var notBefore = 0L

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    class State(
        val items: List<Item>,
        val playlists: List<PlannedPlaylist>,
        val tracks: Map<String, LibraryTrack>,
        // Tracks that already had a local copy when the job was planned: key to file URI.
        // Mutable so a resumed job can adopt files found on disk but missing from the
        // database. The Java field type is still java.util.Map for old saved states.
        var existing: MutableMap<String, String>,
        val unloadedPlaylists: List<String>
    ) : Serializable {
        var pausedReason: String? = null
        var playlistFilesWritten = 0

        fun count(status: Status) = items.count { it.status == status }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    // What planning found, for the confirmation dialog.
    class Prepared(
        val plan: LibraryPlan,
        val localPlaylists: Int,
        val remotePlaylists: Int,
        val unloadedPlaylists: List<String>,
        val existing: Map<String, String>,
        val scannedFiles: Int,
        val matchedOnDisk: Int
    )

    private val main = Handler(Looper.getMainLooper())
    private val disposables = CompositeDisposable()
    private var state: State? = null
    private var appContext: Context? = null
    private var binder: DownloadManagerService.DownloadManagerBinder? = null
    private var connection: ServiceConnection? = null
    private var libraryDir: BulkDownloadInitiator.SubdirectoryResult? = null
    private var networkFailures = 0
    private var saveScheduled = false
    private var finishing = false
    private var starting = false

    @JvmStatic
    fun isRunning(): Boolean = connection != null

    // The saved job, if any, loading it from disk the first time.
    @JvmStatic
    fun savedState(context: Context): State? {
        state?.let { return it }
        val file = File(context.filesDir, STATE_FILE)
        if (!file.exists()) return null
        return try {
            ObjectInputStream(file.inputStream().buffered()).use { it.readObject() as State }
                .also { loaded ->
                    // Older states stored an immutable map; make it safe to extend on resume.
                    loaded.existing = loaded.existing.toMutableMap()
                    state = loaded
                }
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable library download state", e)
            null
        }
    }

    @JvmStatic
    fun discard(context: Context) {
        if (isRunning() || starting) return
        state = null
        File(context.filesDir, STATE_FILE).delete()
    }

    // Collects the library and plans the download, off the main thread.
    @JvmStatic
    fun prepare(context: Context): Single<Prepared> = Single.fromCallable { gather(context.applicationContext) }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())

    @JvmStatic
    fun start(context: Context, prepared: Prepared) {
        if (isRunning() || starting) return
        val items = prepared.plan.toDownload.map { Item(it) }
        state = State(
            items,
            prepared.plan.playlists,
            prepared.plan.tracks,
            prepared.existing.toMutableMap(),
            prepared.unloadedPlaylists
        )
        run(context)
    }

    // Continues a saved job; with retryFailed, failed tracks are tried again from scratch.
    // A state saved by an older build can still list files that are already on the phone:
    // check the download folder before starting, so resuming does not fetch them again.
    @JvmStatic
    fun resume(context: Context, retryFailed: Boolean) {
        if (isRunning() || starting) return
        val s = savedState(context) ?: return
        s.pausedReason = null
        for (item in s.items) {
            if (retryFailed && item.status == Status.FAILED) {
                item.status = Status.PENDING
                item.attempts = 0
                item.error = null
                item.notBefore = 0L
            }
        }
        val app = context.applicationContext
        val tracks = s.tracks
        val plannedNames = plannedNamesFor(s)
        starting = true
        disposables.add(
            Single.fromCallable {
                LibraryFileMatcher.match(tracks, plannedNames, scanAudioFolder(app))
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { found ->
                        applyFoundFiles(s, found)
                        run(app)
                    },
                    { e ->
                        Log.w(TAG, "Could not check existing files before resuming", e)
                        run(app)
                    }
                )
        )
    }

    @JvmStatic
    fun pause(reason: String) {
        val s = state ?: return
        s.pausedReason = reason
        stop()
    }

    // ---------------------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------------------

    private fun gather(context: Context): Prepared {
        val db = NewPipeDatabase.getInstance(context)
        val playlists = ArrayList<LibraryPlaylist>()

        val rows = db.playlistStreamDAO().getLibraryEntriesBlocking()
        var local = 0
        for ((_, group) in rows.groupBy { it.playlistId }) {
            val tracks = group.filter { isStreamUrl(it.url) }.map {
                LibraryTrack(
                    it.serviceId,
                    it.url,
                    it.title,
                    BulkDownloadInitiator.cleanArtistName(it.uploader),
                    it.duration,
                    it.thumbnailUrl
                )
            }
            playlists += LibraryPlaylist(group.first().playlistName ?: "Playlist", tracks)
            local += 1
        }

        val unloaded = ArrayList<String>()
        var remote = 0
        for (bookmark in db.playlistRemoteDAO().getAll().blockingFirst()) {
            val name = bookmark.orderingName ?: bookmark.url ?: "Playlist"
            val url = bookmark.url
            if (url == null) {
                unloaded += name
                continue
            }
            try {
                val info = ExtractorHelper.getPlaylistInfo(bookmark.serviceId, url, false).blockingGet()
                val items = PlaylistFetcher.fetchAllPlaylistItems(bookmark.serviceId, url, info).blockingGet()
                playlists += LibraryPlaylist(name, items.filter { isStreamUrl(it.url) }.map(::trackOf))
                remote += 1
            } catch (e: Exception) {
                Log.w(TAG, "Could not load bookmarked playlist $name", e)
                unloaded += name
            }
        }

        // Offline mappings are only one source of truth. After an update they can be
        // missing, flagged unavailable, or point at SAF URIs the app can no longer
        // resolve, while the audio files themselves are still in the download folder.
        val existing = HashMap<String, String>()
        for (m in db.offlineFileMappingDAO().getAllBlocking()) {
            if (fileExists(context, Uri.parse(m.localFileUri))) {
                existing[LibraryPlanner.trackKey(m.serviceId, m.streamUrl)] = m.localFileUri
            }
        }

        // Plan once with no tracks marked as present to learn the name of every track,
        // then match those names against the files actually on disk. Files found here
        // also reserve their names, so a missing track is never downloaded over one.
        val mappingCount = existing.size
        val allNames = LibraryPlanner.plan(playlists, emptySet())
        val namesByKey = allNames.toDownload.associate { it.key to it.baseName }
        val files = scanAudioFolder(context)
        val matched = LibraryFileMatcher.match(allNames.tracks, namesByKey, files)
        for ((key, uri) in matched) {
            if (!existing.containsKey(key)) existing[key] = uri
        }
        val takenNames = files.mapTo(HashSet()) { LibraryFileMatcher.baseName(it.name) }
        val plan = LibraryPlanner.plan(playlists, existing.keys, takenNames)
        Log.i(
            TAG,
            "Library plan: ${files.size} audio files found, ${matched.size} matched on disk, " +
                "$mappingCount from offline mappings, ${plan.toDownload.size} to download"
        )
        return Prepared(
            plan,
            local,
            remote,
            unloaded,
            existing.filterKeys { it in plan.tracks },
            files.size,
            matched.size
        )
    }

    private fun audioFolderPath(context: Context): String? = PreferenceManager.getDefaultSharedPreferences(context)
        .getString(context.getString(R.string.download_path_audio_key), null)

    private fun scanAudioFolder(context: Context): List<LocalAudioFile> = LibraryFileScanner.scan(context, audioFolderPath(context))

    // Rebuilds the names the planner would give every track of a saved job. The state
    // keeps the planned playlists and tracks, not the original LibraryPlaylist objects.
    private fun plannedNamesFor(s: State): Map<String, String> {
        val playlists = s.playlists.map { p ->
            LibraryPlaylist(p.name, p.keys.mapNotNull { key -> s.tracks[key] })
        }
        return LibraryPlanner.plan(playlists, emptySet()).toDownload
            .associate { it.key to it.baseName }
    }

    // Marks items whose files were found as done and remembers their URI for the .m3u8
    // files. fileName stays null so writePlaylists resolves a relative path from the URI.
    private fun applyFoundFiles(s: State, found: Map<String, String>) {
        if (found.isEmpty()) return
        val byKey = s.items.associateBy { it.planned.key }
        for ((key, uri) in found) {
            if (!s.tracks.containsKey(key)) continue
            if (!s.existing.containsKey(key)) s.existing[key] = uri
            val item = byKey[key]
            if (item != null && item.status != Status.DONE) {
                item.status = Status.DONE
                item.error = null
                item.fileName = null
            }
        }
    }

    private fun trackOf(item: StreamInfoItem) = LibraryTrack(
        item.serviceId,
        item.url,
        item.name,
        BulkDownloadInitiator.cleanArtistName(item.uploaderName),
        item.duration,
        BulkDownloadInitiator.selectHighestQualityThumbnail(item.thumbnails)
    )

    // Corrupt playlist rows can hold a channel URL; those are not tracks.
    private fun isStreamUrl(url: String?) = !url.isNullOrBlank() && "/channel/" !in url

    // ---------------------------------------------------------------------------------
    // Running
    // ---------------------------------------------------------------------------------

    private fun run(context: Context) {
        val app = context.applicationContext
        appContext = app
        starting = false
        networkFailures = 0
        finishing = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val b = service as DownloadManagerService.DownloadManagerBinder
                val storage = b.mainStorageAudio
                if (storage == null) {
                    pause("Choose an audio download folder in Settings, Downloads, then resume.")
                    return
                }
                libraryDir = try {
                    BulkDownloadInitiator.createPlaylistSubdirectory(app, storage, LIBRARY_FOLDER)
                } catch (e: IOException) {
                    Log.e(TAG, "Could not create the library folder", e)
                    pause("Could not create the Library folder: ${e.message}")
                    return
                }
                binder = b
                b.addMissionEventListener(missionListener)
                reconcile(app, b)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder = null
            }
        }
        connection = conn
        if (!app.bindService(Intent(app, DownloadManagerService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            state?.pausedReason = "The download service could not be started."
            saveNow()
        }
        notifyProgress()
    }

    private fun stop() {
        starting = false
        disposables.clear()
        main.removeCallbacks(pumpRunnable)
        binder?.removeMissionEventListener(missionListener)
        connection?.let { conn ->
            try {
                appContext?.unbindService(conn)
            } catch (e: IllegalArgumentException) {
                // Already unbound.
            }
        }
        binder = null
        connection = null
        saveNow()
        notifyProgress()
    }

    // After a restart, downloads that were in flight may have finished, may still be
    // queued in the download list, or may be gone; a failed one may have been resumed
    // from the download list and finished since.
    private fun reconcile(context: Context, b: DownloadManagerService.DownloadManagerBinder) {
        val s = state ?: return
        val unsettled = s.items.filter { it.status == Status.ACTIVE || it.status == Status.FAILED }
        disposables.add(
            Single.fromCallable {
                val mappings = NewPipeDatabase.getInstance(context).offlineFileMappingDAO()
                unsettled.filter { item ->
                    val t = item.planned.track
                    mappings.getByStreamUrlBlocking(t.serviceId, t.url)?.isAvailable == true
                }.mapTo(HashSet()) { it.planned.key }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { mapped -> applyReconcile(context, b, unsettled, mapped) },
                    { e ->
                        Log.w(TAG, "Could not check earlier downloads", e)
                        applyReconcile(context, b, unsettled, emptySet())
                    }
                )
        )
    }

    private fun applyReconcile(
        context: Context,
        b: DownloadManagerService.DownloadManagerBinder,
        unsettled: List<Item>,
        mapped: Set<String>
    ) {
        val dir = libraryDir
        for (item in unsettled) {
            if (item.planned.key in mapped) {
                item.status = Status.DONE
                item.error = null
                continue
            }
            if (item.status != Status.ACTIVE) continue
            val stored = if (dir == null) null else item.fileName?.let { findFile(context, dir, it) }
            val missionState = stored?.let { b.downloadManager.checkForExistingMission(it) } ?: MissionState.None
            if (missionState == MissionState.Pending || missionState == MissionState.PendingRunning) continue
            item.status = Status.PENDING
        }
        pump()
    }

    private val pumpRunnable = Runnable { pump() }

    private fun pump() {
        val s = state ?: return
        if (binder == null || s.pausedReason != null) return
        val now = System.currentTimeMillis()
        var active = s.count(Status.ACTIVE)
        var nextWake = Long.MAX_VALUE
        for (item in s.items) {
            if (active >= IN_FLIGHT) break
            if (item.status != Status.PENDING) continue
            if (item.notBefore > now) {
                nextWake = minOf(nextWake, item.notBefore)
                continue
            }
            startItem(item)
            active += 1
        }
        main.removeCallbacks(pumpRunnable)
        if (nextWake != Long.MAX_VALUE) main.postDelayed(pumpRunnable, nextWake - now)
        if (active == 0 && s.count(Status.PENDING) == 0 && !finishing) {
            finishing = true
            finish()
        }
        scheduleSave()
    }

    private fun startItem(item: Item) {
        val context = appContext ?: return
        item.status = Status.ACTIVE
        item.attempts += 1
        val t = item.planned.track
        // Every download attempt extracts afresh, including a user-requested retry that
        // resets attempts to zero. Cached empty stream lists must not poison retries.
        disposables.add(
            ExtractorHelper.getStreamInfo(t.serviceId, t.url, true)
                .subscribeOn(Schedulers.io())
                .map { info -> queueDownload(context, item, info) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { fileName ->
                        item.fileName = fileName
                        networkFailures = 0
                        scheduleSave()
                    },
                    { e -> onStartFailed(item, e) }
                )
        )
    }

    private fun onStartFailed(item: Item, e: Throwable) {
        val s = state ?: return
        Log.w(TAG, "Could not start ${item.planned.track.url}", e)
        when {
            causedBy<ReCaptchaException>(e) -> {
                item.status = Status.PENDING
                item.attempts -= 1
                pause("YouTube asked for a captcha. Play any video in the app to solve it, then resume.")
                return
            }

            e is IllegalStateException -> {
                // Android refused to start the download service from the background.
                item.status = Status.PENDING
                item.attempts -= 1
                pause("Android paused the download while the app was in the background. Open the app and resume.")
                return
            }

            causedBy<ContentNotAvailableException>(e) -> {
                item.status = Status.FAILED
                item.error = e.message ?: "Not available"
            }

            item.attempts >= MAX_ATTEMPTS -> {
                item.status = Status.FAILED
                item.error = e.message ?: e.javaClass.simpleName
            }

            else -> {
                item.status = Status.PENDING
                item.notBefore = System.currentTimeMillis() + RETRY_DELAY_MILLIS * item.attempts
            }
        }
        if (causedBy<IOException>(e)) {
            networkFailures += 1
            if (networkFailures >= PAUSE_AFTER_NETWORK_FAILURES) {
                if (item.status == Status.FAILED) {
                    // A dropped connection is not the track's fault.
                    item.status = Status.PENDING
                    item.attempts = 0
                }
                pause("Lost the connection. Resume when you are back online.")
                return
            }
        }
        if (s.pausedReason == null) pump()
    }

    private val missionListener = Handler.Callback { msg: Message ->
        val mission = msg.obj as? DownloadMission
        val item = mission?.let(::itemFor)
        if (item != null) {
            when (msg.what) {
                DownloadManagerService.MESSAGE_FINISHED -> {
                    item.status = Status.DONE
                    item.error = null
                    item.fileName = mission.storage.name
                    pump()
                }

                DownloadManagerService.MESSAGE_ERROR -> if (item.status == Status.ACTIVE) {
                    // The download list keeps the mission and can resume it; it no longer
                    // holds one of this job's slots, and finishing later still counts.
                    item.status = Status.FAILED
                    item.error = "Download failed (error ${mission.errCode}); it can be resumed in Downloads"
                    pump()
                }
            }
        }
        false
    }

    private fun itemFor(mission: DownloadMission): Item? {
        val meta = mission.playlistMetadata ?: return null
        val url = meta.streamUrl ?: return null
        val key = LibraryPlanner.trackKey(meta.serviceId, url)
        return state?.items?.firstOrNull { it.planned.key == key }
    }

    // Runs on an IO thread. Returns the name the file was created under.
    private fun queueDownload(context: Context, item: Item, info: StreamInfo): String {
        val planned = item.planned
        val t = planned.track
        val streams: List<AudioStream> =
            ListHelper.getStreamsOfSpecifiedDelivery(info.audioStreams, DeliveryMethod.PROGRESSIVE_HTTP)
        if (streams.isEmpty()) {
            val deliveryCounts = info.audioStreams.groupingBy { it.deliveryMethod }.eachCount()
            throw NoAudioException(
                "Extractor returned ${info.audioStreams.size} audio streams " +
                    "($deliveryCounts); none use progressive HTTP supported by the downloader. " +
                    "Video streams: ${info.videoStreams.size}; video-only streams: ${info.videoOnlyStreams.size}."
            )
        }
        val stream = streams[ListHelper.getDefaultAudioFormat(context, streams)]
        val format = stream.format
        val opus = format == MediaFormat.WEBMA_OPUS
        val conversion = when (format) {
            MediaFormat.M4A -> Postprocessing.ALGORITHM_M4A_NO_DASH
            MediaFormat.WEBMA_OPUS -> Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER
            else -> null
        }
        val (psName, psArgs) = if (conversion != null) {
            Postprocessing.ALGORITHM_COMPOSITE to arrayOf(
                conversion,
                Postprocessing.ALGORITHM_AUDIO_METADATA_TAGGING,
                planned.trackNumber.toString(),
                planned.album,
                t.artist,
                t.title,
                t.thumbnailUrl ?: "",
                BulkDownloadInitiator.containerKindFor(format),
                t.url
            )
        } else {
            null to null
        }
        val ext = if (opus) "opus" else format?.suffix ?: "m4a"
        val mime = if (opus) "audio/ogg" else format?.mimeType ?: "audio/mp4"
        val storage = createFile(context, libraryDir ?: throw IOException("No library folder"), "${planned.baseName}.$ext", mime)

        try {
            NewPipeDatabase.getInstance(context).streamDAO().upsert(StreamEntity(info))
        } catch (e: Exception) {
            Log.w(TAG, "Could not save stream metadata", e)
        }

        val meta = DownloadMission.PlaylistMetadata().apply {
            serviceId = t.serviceId
            playlistName = planned.album
            trackPosition = planned.trackNumber
            playlistThumbnailUrl = t.thumbnailUrl
            uploaderName = t.artist
            videoTitle = t.title
            streamUrl = t.url
        }
        DownloadManagerService.startMission(
            context,
            arrayOf(stream.content),
            storage,
            'a',
            3,
            info,
            psName,
            psArgs,
            0,
            arrayListOf(MissionRecoveryInfo(stream)),
            meta,
            true
        )
        return storage.name
    }

    // A file left by an earlier, unfinished attempt is replaced, so names stay clean.
    private fun createFile(
        context: Context,
        dir: BulkDownloadInitiator.SubdirectoryResult,
        name: String,
        mime: String
    ): StoredFileHelper {
        val saf = dir.safSubdirectory
        if (saf != null) {
            saf.findFile(name)?.delete()
            val file = saf.createFile(mime, name) ?: throw IOException("Could not create $name")
            return StoredFileHelper(context, file.uri, mime)
        }
        val helper: StoredDirectoryHelper = dir.directoryHelper ?: throw IOException("No library folder")
        helper.findFile(name)?.path?.let { File(it).delete() }
        return helper.createFile(name, mime) ?: throw IOException("Could not create $name")
    }

    private fun findFile(context: Context, dir: BulkDownloadInitiator.SubdirectoryResult, name: String): StoredFileHelper? {
        val uri = dir.safSubdirectory?.findFile(name)?.uri ?: dir.directoryHelper?.findFile(name) ?: return null
        return try {
            StoredFileHelper(context, uri, "audio/*")
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------
    // Finishing: playlist files
    // ---------------------------------------------------------------------------------

    private fun finish() {
        val s = state ?: return
        val context = appContext ?: return
        val dir = libraryDir ?: return
        disposables.add(
            Single.fromCallable { writePlaylists(context, s, dir) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { written ->
                        s.playlistFilesWritten = written
                        stop()
                    },
                    { e ->
                        Log.e(TAG, "Could not write playlist files", e)
                        s.pausedReason = "Downloads finished, but the playlist files could not be written: ${e.message}"
                        stop()
                    }
                )
        )
    }

    private fun writePlaylists(context: Context, s: State, dir: BulkDownloadInitiator.SubdirectoryResult): Int {
        val dirId = pathId(context, dir.safSubdirectory?.uri ?: dir.directoryHelper?.uri) ?: return 0
        val done = s.items.filter { it.status == Status.DONE && it.fileName != null }
            .associate { it.planned.key to it.fileName!! }
        var written = 0
        for (playlist in s.playlists) {
            val entries = playlist.keys.mapNotNull { key ->
                val t = s.tracks[key] ?: return@mapNotNull null
                val path = done[key]
                    ?: s.existing[key]?.let { pathId(context, Uri.parse(it)) }?.let { RelativePath.between(dirId, it) }
                    ?: return@mapNotNull null
                M3uEntry(path, t.durationSeconds, LibraryPlanner.displayName(t))
            }
            val text = M3u.write(playlist.name, entries)
            writeText(context, dir, playlist.fileName, text)
            written += 1
        }
        return written
    }

    private fun writeText(context: Context, dir: BulkDownloadInitiator.SubdirectoryResult, name: String, text: String) {
        val saf: DocumentFile? = dir.safSubdirectory
        if (saf != null) {
            saf.findFile(name)?.delete()
            // A generic type, so the provider keeps the .m3u8 name as given.
            val file = saf.createFile("application/octet-stream", name) ?: throw IOException("Could not create $name")
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
                ?: throw IOException("Could not write $name")
            return
        }
        val path = dir.directoryHelper?.uri?.path ?: throw IOException("No library folder")
        File(path, name).writeText(text)
    }

    // A path-like id for RelativePath: "volume:dir/file" for storage documents, or the
    // file path.
    private fun pathId(context: Context, uri: Uri?): String? {
        uri ?: return null
        return when (uri.scheme) {
            "file" -> uri.path

            "content" -> try {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.getDocumentId(uri)
                } else {
                    DocumentsContract.getTreeDocumentId(uri)
                }
            } catch (e: Exception) {
                null
            }

            else -> null
        }
    }

    // ---------------------------------------------------------------------------------
    // Persistence and progress
    // ---------------------------------------------------------------------------------

    private val saveRunnable = Runnable {
        saveScheduled = false
        saveNow()
        notifyProgress()
    }

    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed(saveRunnable, SAVE_DELAY_MILLIS)
    }

    private fun saveNow() {
        val s = state ?: return
        val context = appContext ?: return
        main.removeCallbacks(saveRunnable)
        saveScheduled = false
        // Serialised here, on the main thread, so the snapshot is consistent.
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(s) }
        }.toByteArray()
        Schedulers.io().scheduleDirect {
            try {
                val target = File(context.filesDir, STATE_FILE)
                val tmp = File(context.filesDir, "$STATE_FILE.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) target.writeBytes(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Could not save library download state", e)
            }
        }
    }

    @JvmStatic
    fun describe(s: State): String {
        val done = s.count(Status.DONE)
        val failed = s.count(Status.FAILED)
        val left = s.count(Status.PENDING) + s.count(Status.ACTIVE)
        return buildString {
            append("$done of ${s.items.size} downloaded")
            if (failed > 0) append(", $failed failed")
            if (left > 0) append(", $left to go")
            if (s.existing.isNotEmpty()) append(" (${s.existing.size} were already on the phone)")
            s.pausedReason?.let { append(". Paused: ").append(it) }
            if (left == 0 && s.playlistFilesWritten > 0) append(". ${s.playlistFilesWritten} playlist files written")
        }
    }

    private fun notifyProgress() {
        val context = appContext ?: return
        val s = state ?: return
        val total = s.items.size
        val settled = s.count(Status.DONE) + s.count(Status.FAILED)
        val running = isRunning()
        val builder = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setSmallIcon(if (running) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (running) "Downloading library" else "Library download")
            .setContentText(describe(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(describe(s)))
            .setOnlyAlertOnce(true)
            .setOngoing(running)
        if (running && total > 0) builder.setProgress(total, settled, false)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Notifications not permitted; the settings entry still shows progress.
        }
    }

    private fun fileExists(context: Context, uri: Uri): Boolean = try {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.let { it.exists() && it.length() > 0 } ?: false
            "content" -> DocumentFile.fromSingleUri(context, uri)?.let { it.exists() && it.length() > 0 } ?: false
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    private inline fun <reified T : Throwable> causedBy(e: Throwable): Boolean {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 10) {
            if (c is T) return true
            c = c.cause
            depth += 1
        }
        return false
    }

    private class NoAudioException(details: String) : Exception("No downloadable audio stream. $details")
}
