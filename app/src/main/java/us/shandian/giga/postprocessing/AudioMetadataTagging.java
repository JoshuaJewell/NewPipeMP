package us.shandian.giga.postprocessing;

import android.util.Log;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.util.rating.OpusTagEditor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Post-processor that adds metadata tags to audio files.
 * Supports MP3, M4A, FLAC, and Opus containers.
 *
 * Arguments:
 * [0] = track number (playlist position)
 * [1] = album name (playlist name)
 * [2] = artist name (uploader name)
 * [3] = title (video title)
 * [4] = thumbnail URL (for album art)
 * [5] = container kind: "mp3" | "m4a" | "flac" | "opus" (defaults to "m4a"
 *       when absent, to preserve pre-acetate behaviour). Opus is routed
 *       through {@link OpusTagEditor} because JAudioTagger 3.0.1 does not
 *       recognise the OpusHead identification packet.
 */
class AudioMetadataTagging extends Postprocessing {
    private static final String TAG = "AudioMetadataTagging";
    private static final int BUFFER_SIZE = 8192;

    AudioMetadataTagging() {
        super(false, true, "audio-metadata");
    }

    @Override
    int process(SharpStream out, SharpStream... sources) throws IOException {
        Log.d(TAG, "AudioMetadataTagging.process() called");

        // Get arguments
        String trackNumber = getArgumentAt(0, null);
        String albumName = getArgumentAt(1, null);
        String artistName = getArgumentAt(2, null);
        String title = getArgumentAt(3, null);
        String thumbnailUrl = getArgumentAt(4, null);
        String container = getArgumentAt(5, "m4a");

        Log.d(TAG, "Received arguments - track: " + trackNumber + ", album: " + albumName
            + ", artist: " + artistName + ", title: " + title + ", thumbnail: " + thumbnailUrl
            + ", container: " + container);

        if (trackNumber == null || albumName == null) {
            Log.w(TAG, "Missing required metadata (track number or album name), skipping tagging");
            // Copy source to output without tagging
            copyStream(sources[0], out);
            return OK_RESULT;
        }

        File tempAudioFile = null;
        File tempThumbnailFile = null;

        try {
            // Create temporary file with the container's extension so the
            // tagging library picks the right dialect.
            tempAudioFile = File.createTempFile("newpipe_audio_", "." + container);

            // Copy source stream to temporary file
            try (FileOutputStream fos = new FileOutputStream(tempAudioFile)) {
                copyStream(sources[0], fos);
            }

            // Download artwork if a URL was provided.
            if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                try {
                    tempThumbnailFile = downloadThumbnail(thumbnailUrl);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to download album art: " + e.getMessage());
                }
            }

            if ("opus".equalsIgnoreCase(container)) {
                tagOpus(tempAudioFile, trackNumber, albumName, artistName, title,
                        tempThumbnailFile);
            } else {
                tagViaJAudioTagger(tempAudioFile, trackNumber, albumName, artistName, title,
                        tempThumbnailFile);
            }

            Log.d(TAG, String.format(
                "Successfully tagged audio file: track=%s, album=%s, artist=%s, title=%s",
                trackNumber, albumName, artistName, title
            ));

            // Copy tagged file back to output stream
            try (FileInputStream fis = new FileInputStream(tempAudioFile)) {
                copyStream(fis, out);
            }

            return OK_RESULT;

        } catch (Exception e) {
            Log.e(TAG, "Failed to tag audio file", e);
            // On error, try to copy original stream to output
            try {
                sources[0].rewind();
                copyStream(sources[0], out);
            } catch (IOException rewindError) {
                Log.e(TAG, "Failed to rewind and copy original stream", rewindError);
                throw new IOException("Audio tagging failed and could not recover", e);
            }
            // Return OK to allow download to complete even if tagging failed
            return OK_RESULT;

        } finally {
            // Clean up temporary files
            if (tempAudioFile != null && tempAudioFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempAudioFile.delete();
            }
            if (tempThumbnailFile != null && tempThumbnailFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempThumbnailFile.delete();
            }
        }
    }

    /**
     * Writes Vorbis-style comments (and optional artwork) into a `.opus` file
     * via {@link OpusTagEditor}, bypassing JAudioTagger.
     */
    private void tagOpus(File audio, String trackNumber, String albumName,
                         String artistName, String title, File thumbnailFile) throws IOException {
        Map<String, String> comments = new LinkedHashMap<>();
        if (title != null) {
            comments.put("TITLE", title);
        }
        if (artistName != null) {
            comments.put("ARTIST", artistName);
        }
        if (albumName != null) {
            comments.put("ALBUM", albumName);
        }
        if (trackNumber != null) {
            comments.put("TRACKNUMBER", trackNumber);
        }

        byte[] artworkBytes = null;
        String artworkMime = null;
        if (thumbnailFile != null && thumbnailFile.exists()) {
            artworkBytes = Files.readAllBytes(thumbnailFile.toPath());
            artworkMime = mimeFromExtension(thumbnailFile.getName());
        }

        new OpusTagEditor(audio).writeFullComments(comments, artworkBytes, artworkMime);
    }

    /**
     * Writes tags via JAudioTagger for any container it supports (MP3, FLAC,
     * M4A). Opus must not reach this path; see {@link #tagOpus}.
     */
    private void tagViaJAudioTagger(File audio, String trackNumber, String albumName,
                                     String artistName, String title, File thumbnailFile)
            throws Exception {
        AudioFile audioFile = AudioFileIO.read(audio);
        Tag tag = audioFile.getTagOrCreateAndSetDefault();
        if (trackNumber != null) {
            tag.setField(FieldKey.TRACK, trackNumber);
        }
        if (albumName != null) {
            tag.setField(FieldKey.ALBUM, albumName);
        }
        if (artistName != null) {
            tag.setField(FieldKey.ARTIST, artistName);
        }
        if (title != null) {
            tag.setField(FieldKey.TITLE, title);
        }

        if (thumbnailFile != null && thumbnailFile.exists()) {
            try {
                Artwork artwork = ArtworkFactory.createArtworkFromFile(thumbnailFile);
                tag.setField(artwork);
            } catch (Exception e) {
                Log.w(TAG, "Failed to embed album art: " + e.getMessage());
            }
        }

        audioFile.commit();
    }

    private static String mimeFromExtension(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /**
     * Downloads a thumbnail from the given URL to a temporary file.
     *
     * @param urlString the URL of the thumbnail
     * @return temporary file containing the thumbnail, or null if download failed
     */
    private File downloadThumbnail(String urlString) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        File tempFile = null;

        try {
            // Determine image extension from URL
            String extension = ".jpg"; // default
            if (urlString.toLowerCase().endsWith(".png")) {
                extension = ".png";
            } else if (urlString.toLowerCase().endsWith(".webp")) {
                extension = ".webp";
            }

            tempFile = File.createTempFile("newpipe_thumb_", extension);

            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); // 10 seconds
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Thumbnail download failed with HTTP " + responseCode);
                return null;
            }

            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            return tempFile;

        } catch (Exception e) {
            Log.w(TAG, "Failed to download thumbnail: " + e.getMessage());
            if (tempFile != null && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            return null;

        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Copies data from an input stream to an output stream.
     *
     * @param in  input stream
     * @param out output stream
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Copies data from a SharpStream to an output stream.
     *
     * @param in  input SharpStream
     * @param out output stream
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(SharpStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Copies data from an input stream to a SharpStream.
     *
     * @param in  input stream
     * @param out output SharpStream
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream in, SharpStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Copies data from a SharpStream to a SharpStream.
     *
     * @param in  input SharpStream
     * @param out output SharpStream
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(SharpStream in, SharpStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }
}
