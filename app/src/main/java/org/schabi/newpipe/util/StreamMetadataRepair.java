package org.schabi.newpipe.util;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.download.dao.OfflineFileMappingDAO;
import org.schabi.newpipe.database.download.model.OfflineFileMappingEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Utility to repair corrupted StreamEntity metadata by extracting it from downloaded files.
 * This is useful when database entries have empty/corrupted metadata but the downloaded
 * files contain correct embedded metadata (ID3 tags, etc.).
 */
public final class StreamMetadataRepair {
    private static final String TAG = "StreamMetadataRepair";

    /**
     * Reads the rating tag from a local audio file. The file is the source of
     * truth for ratings; {@code streams.user_rating} is a cache.
     *
     * @param file the local audio file to read the rating tag from
     * @return the 1..10 rating, or null on absence, unsupported container, or
     *         parse failure (never throws)
     */
    public static Integer readRatingFromFile(@NonNull final java.io.File file) {
        return org.schabi.newpipe.util.rating.RatingTagReader.INSTANCE.read(file);
    }

    private StreamMetadataRepair() {
        // Utility class
    }

    /**
     * Repairs all corrupted StreamEntity entries by extracting metadata from their
     * corresponding offline files.
     *
     * @param context the context
     * @return Single that emits the number of repaired entries
     */
    public static Single<Integer> repairAllCorruptedEntries(@NonNull final Context context) {
        return Single.fromCallable(() -> {
            final AppDatabase database = NewPipeDatabase.getInstance(context);
            final StreamDAO streamDAO = database.streamDAO();
            final OfflineFileMappingDAO mappingDAO = database.offlineFileMappingDAO();

            // Get all offline file mappings
            final List<OfflineFileMappingEntity> mappings =
                    mappingDAO.getAllAvailableMappings().blockingFirst();

            int repairedCount = 0;

            for (final OfflineFileMappingEntity mapping : mappings) {
                try {
                    // Get the StreamEntity for this mapping
                    final List<StreamEntity> entities = streamDAO.getStream(
                            mapping.getServiceId(), mapping.getStreamUrl()).blockingFirst();

                    if (entities.isEmpty()) {
                        Log.w(TAG, "No StreamEntity found for: " + mapping.getStreamUrl());
                        continue;
                    }

                    final StreamEntity entity = entities.get(0);

                    // Check if entity needs repair (has empty/corrupted metadata)
                    if (!StreamMetadataDamage.isBadTitle(entity.getTitle(), entity.getUrl(),
                            entity.getUploaderUrl())
                            && !StreamMetadataDamage.isBadUploader(entity.getUploader())
                            && entity.getThumbnailUrl() != null
                            && !entity.getThumbnailUrl().isEmpty()) {
                        // Metadata looks good, skip
                        continue;
                    }

                    Log.i(TAG, "Repairing metadata for: " + entity.getUrl());
                    Log.d(TAG, "Current - Title: '" + entity.getTitle()
                            + "', Uploader: '" + entity.getUploader() + "'");

                    // Extract metadata from the offline file
                    final String fileUri = mapping.getLocalFileUri();
                    final MetadataExtractionResult extracted =
                            extractMetadataFromFile(context, fileUri);

                    if (extracted == null) {
                        Log.w(TAG, "Failed to extract metadata from file: " + fileUri);
                        continue;
                    }

                    // Update the entity with extracted metadata
                    boolean updated = false;

                    if (StreamMetadataDamage.isBadTitle(entity.getTitle(), entity.getUrl(),
                            entity.getUploaderUrl())
                            && extracted.title != null && !extracted.title.isEmpty()) {
                        entity.setTitle(extracted.title);
                        updated = true;
                        Log.i(TAG, "Updated title to: " + extracted.title);
                    }

                    if (StreamMetadataDamage.isBadUploader(entity.getUploader())
                            && extracted.artist != null && !extracted.artist.isEmpty()) {
                        entity.setUploader(extracted.artist);
                        updated = true;
                        Log.i(TAG, "Updated uploader to: " + extracted.artist);
                    }

                    // Save album art if available and entity has no thumbnail
                    if ((entity.getThumbnailUrl() == null || entity.getThumbnailUrl().isEmpty()
                            || entity.getThumbnailUrl().startsWith("data:"))
                            && extracted.albumArtBytes != null
                            && extracted.albumArtBytes.length > 0) {
                        final String thumbnailPath = saveAlbumArtToFile(
                                context, entity.getUrl(), extracted.albumArtBytes);
                        if (thumbnailPath != null) {
                            entity.setThumbnailUrl(thumbnailPath);
                            updated = true;
                            Log.i(TAG, "Saved album art to: " + thumbnailPath);
                        }
                    }

                    if (updated) {
                        // Update the database
                        streamDAO.upsert(entity);
                        repairedCount++;
                        Log.i(TAG, "Successfully repaired metadata for: " + entity.getTitle());
                    }

                    // Sync the in-file rating tag into the Room cache. The file
                    // is the source of truth (see acetate spec §3.1); Room is a
                    // cache. file:// URIs only; SAF (content://) is out of
                    // scope for this plan.
                    final android.net.Uri uri = android.net.Uri.parse(fileUri);
                    if (uri.getPath() != null
                            && "file".equals(uri.getScheme())) {
                        final Integer fileRating = readRatingFromFile(
                                new java.io.File(uri.getPath()));
                        if (fileRating != null
                                && !fileRating.equals(entity.getUserRating())) {
                            final long sid = streamDAO.upsert(entity);
                            streamDAO.updateRating(sid, fileRating);
                            Log.i(TAG, "Synced rating tag (" + fileRating
                                    + ") from file for: " + entity.getTitle());
                        }
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "Error repairing metadata for mapping: "
                            + mapping.getStreamUrl(), e);
                }
            }

            Log.i(TAG, "Metadata repair complete. Repaired " + repairedCount + " entries.");
            return repairedCount;
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Extracts metadata from a media file.
     *
     * @param context the context
     * @param fileUri the file URI
     * @return extracted metadata, or null if extraction failed
     */
    private static MetadataExtractionResult extractMetadataFromFile(
            @NonNull final Context context,
            @NonNull final String fileUri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, Uri.parse(fileUri));

            final String title = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE);
            final String artist = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST);
            final String album = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM);
            final byte[] albumArt = retriever.getEmbeddedPicture();

            if (title == null && artist == null && albumArt == null) {
                return null;
            }

            final MetadataExtractionResult result = new MetadataExtractionResult();
            result.title = title;
            result.artist = artist;
            result.album = album;
            result.albumArtBytes = albumArt;
            final String durationMillis = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMillis != null) {
                try {
                    result.durationSeconds = Long.parseLong(durationMillis.trim()) / 1000;
                } catch (final NumberFormatException ignored) {
                    // No usable duration
                }
            }

            return result;
        } catch (final Exception e) {
            Log.e(TAG, "Failed to extract metadata from file: " + fileUri, e);
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (final Exception e) {
                    // Log but don't fail - resource cleanup errors shouldn't block execution
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
    }

    /**
     * Saves album art bytes to a file and returns the file path.
     *
     * @param context   the context
     * @param streamUrl the stream URL (used to generate unique filename)
     * @param artBytes  the album art bytes
     * @return the file path, or null if save failed
     */
    private static String saveAlbumArtToFile(
            @NonNull final Context context,
            @NonNull final String streamUrl,
            @NonNull final byte[] artBytes) {
        try {
            // Create thumbnails directory
            final File thumbnailsDir = new File(context.getFilesDir(), "thumbnails");
            if (!thumbnailsDir.exists() && !thumbnailsDir.mkdirs()) {
                Log.e(TAG, "Failed to create thumbnails directory");
                return null;
            }

            // Generate filename from stream URL hash
            final String filename = "thumb_" + Math.abs(streamUrl.hashCode()) + ".jpg";
            final File thumbnailFile = new File(thumbnailsDir, filename);

            // Write album art to file
            try (FileOutputStream fos = new FileOutputStream(thumbnailFile)) {
                fos.write(artBytes);
            }

            return "file://" + thumbnailFile.getAbsolutePath();
        } catch (final Exception e) {
            Log.e(TAG, "Failed to save album art", e);
            return null;
        }
    }

    /**
     * Result of metadata extraction from a file.
     */
    private static final class MetadataExtractionResult {
        String title;
        String artist;
        String album;
        byte[] albumArtBytes;
        long durationSeconds = -1;
    }

    /** What {@link #repairDamagedTitles} did. */
    public static final class Report {
        /** Rows found damaged. */
        public int damaged;
        /** Repaired from a downloaded file's tags or name. */
        public int fromFile;
        /** Repaired by fetching the stream's info again. */
        public int fromNetwork;
        /** Left damaged: no usable file, and no fetch (offline, failed, or over the cap). */
        public int remaining;
    }

    /** At most this many streams are fetched again per run. */
    private static final int MAX_NETWORK_REPAIRS = 200;

    /**
     * Repairs streams whose title is missing or is a URL (see {@link StreamMetadataDamage}):
     * from the downloaded file's tags, then from its name, then, if allowed, by fetching the
     * stream's info again. Fills a missing uploader and duration from the same source.
     *
     * @param context      the context
     * @param allowNetwork whether streams with no usable file may be fetched again
     * @return what was repaired
     */
    public static Single<Report> repairDamagedTitles(@NonNull final Context context,
                                                     final boolean allowNetwork) {
        return Single.fromCallable(() -> {
            final AppDatabase database = NewPipeDatabase.getInstance(context);
            final StreamDAO streamDAO = database.streamDAO();
            final OfflineFileMappingDAO mappingDAO = database.offlineFileMappingDAO();
            final Report report = new Report();
            int fetched = 0;
            for (final StreamEntity entity : streamDAO.getStreamsWithSuspectTitlesBlocking()) {
                if (!StreamMetadataDamage.isBadTitle(entity.getTitle(), entity.getUrl(),
                        entity.getUploaderUrl())) {
                    continue;
                }
                report.damaged++;
                try {
                    if (repairFromFile(context, streamDAO, mappingDAO, entity)) {
                        report.fromFile++;
                    } else if (allowNetwork && fetched < MAX_NETWORK_REPAIRS) {
                        fetched++;
                        if (repairFromNetwork(streamDAO, entity)) {
                            report.fromNetwork++;
                        } else {
                            report.remaining++;
                        }
                    } else {
                        report.remaining++;
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "Could not repair " + entity.getUrl(), e);
                    report.remaining++;
                }
            }
            Log.i(TAG, "Title repair: " + report.damaged + " damaged, " + report.fromFile
                    + " from files, " + report.fromNetwork + " fetched, " + report.remaining
                    + " left");
            return report;
        }).subscribeOn(Schedulers.io());
    }

    private static boolean repairFromFile(@NonNull final Context context,
                                          @NonNull final StreamDAO streamDAO,
                                          @NonNull final OfflineFileMappingDAO mappingDAO,
                                          @NonNull final StreamEntity entity) {
        final OfflineFileMappingEntity mapping = mappingDAO.getByStreamUrlBlocking(
                entity.getServiceId(), entity.getUrl());
        if (mapping == null || mapping.getLocalFileUri() == null) {
            return false;
        }
        final String fileUri = mapping.getLocalFileUri();
        final MetadataExtractionResult tags = extractMetadataFromFile(context, fileUri);
        String title = tags != null ? tags.title : null;
        String artist = tags != null ? tags.artist : null;
        if (StreamMetadataDamage.isBadTitle(title, entity.getUrl(), entity.getUploaderUrl())) {
            String name = Uri.parse(fileUri).getLastPathSegment();
            if (name != null) {
                name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("^.*:", "");
                final StreamMetadataDamage.FromFileName parsed =
                        StreamMetadataDamage.fromFileName(name);
                if (parsed != null) {
                    title = parsed.getTitle();
                    if (StreamMetadataDamage.isBadUploader(artist)) {
                        artist = parsed.getArtist();
                    }
                }
            }
        }
        if (StreamMetadataDamage.isBadTitle(title, entity.getUrl(), entity.getUploaderUrl())) {
            return false;
        }
        entity.setTitle(title);
        if (StreamMetadataDamage.isBadUploader(entity.getUploader())
                && !StreamMetadataDamage.isBadUploader(artist)) {
            entity.setUploader(artist);
        }
        if (entity.getDuration() <= 0 && tags != null && tags.durationSeconds > 0) {
            entity.setDuration(tags.durationSeconds);
        }
        streamDAO.upsert(entity);
        return true;
    }

    private static boolean repairFromNetwork(@NonNull final StreamDAO streamDAO,
                                             @NonNull final StreamEntity entity) {
        try {
            final org.schabi.newpipe.extractor.stream.StreamInfo info =
                    org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                            org.schabi.newpipe.extractor.NewPipe.getService(
                                    entity.getServiceId()), entity.getUrl());
            if (StreamMetadataDamage.isBadTitle(info.getName(), info.getUrl(),
                    info.getUploaderUrl())) {
                return false;
            }
            final StreamEntity fresh = new StreamEntity(info);
            // Keep the row (and with it playlists, history and statistics) it belongs to
            fresh.setUrl(entity.getUrl());
            streamDAO.upsert(fresh);
            return true;
        } catch (final Exception e) {
            Log.w(TAG, "Could not fetch " + entity.getUrl() + ": " + e.getMessage());
            return false;
        }
    }
}
