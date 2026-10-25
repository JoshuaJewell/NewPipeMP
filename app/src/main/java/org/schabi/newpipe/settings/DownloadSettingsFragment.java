package org.schabi.newpipe.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.nononsenseapps.filepicker.Utils;

import org.schabi.newpipe.R;
import org.schabi.newpipe.download.LibraryDownloadJob;
import org.schabi.newpipe.download.LibraryRetagDialog;
import org.schabi.newpipe.download.LibraryRetagJob;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.util.FilePickerActivityHelper;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class DownloadSettingsFragment extends BasePreferenceFragment {
    public static final boolean IGNORE_RELEASE_ON_OLD_PATH = true;
    private String downloadPathVideoPreference;
    private String downloadPathAudioPreference;
    private String storageUseSafPreference;

    private Preference prefPathVideo;
    private Preference prefPathAudio;
    private Preference prefStorageAsk;

    private Context ctx;
    private final CompositeDisposable libraryDisposables = new CompositeDisposable();
    private final ActivityResultLauncher<Intent> requestDownloadVideoPathLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadVideoPathResult);
    private final ActivityResultLauncher<Intent> requestDownloadAudioPathLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadAudioPathResult);

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        downloadPathVideoPreference = getString(R.string.download_path_video_key);
        downloadPathAudioPreference = getString(R.string.download_path_audio_key);
        storageUseSafPreference = getString(R.string.storage_use_saf);
        final String downloadStorageAsk = getString(R.string.downloads_storage_ask);

        prefPathVideo = findPreference(downloadPathVideoPreference);
        prefPathAudio = findPreference(downloadPathAudioPreference);
        prefStorageAsk = findPreference(downloadStorageAsk);

        final SwitchPreferenceCompat prefUseSaf = findPreference(storageUseSafPreference);
        prefUseSaf.setChecked(NewPipeSettings.useStorageAccessFramework(ctx));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prefUseSaf.setEnabled(false);
            prefUseSaf.setSummary(R.string.downloads_storage_use_saf_summary_api_29);
            prefStorageAsk.setSummary(R.string.downloads_storage_ask_summary_no_saf_notice);
        }

        updatePreferencesSummary();
        updatePathPickers(!defaultPreferences.getBoolean(downloadStorageAsk, false));

        if (hasInvalidPath(downloadPathVideoPreference)
                || hasInvalidPath(downloadPathAudioPreference)) {
            updatePreferencesSummary();
        }

        prefStorageAsk.setOnPreferenceChangeListener((preference, value) -> {
            updatePathPickers(!(boolean) value);
            return true;
        });
    }

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        ctx = context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        ctx = null;
        prefStorageAsk.setOnPreferenceChangeListener(null);
    }

    private void updatePreferencesSummary() {
        showPathInSummary(downloadPathVideoPreference, R.string.download_path_summary,
                prefPathVideo);
        showPathInSummary(downloadPathAudioPreference, R.string.download_path_audio_summary,
                prefPathAudio);
    }

    private void showPathInSummary(final String prefKey, @StringRes final int defaultString,
                                   final Preference target) {
        final Uri uri = Uri.parse(defaultPreferences.getString(prefKey, ""));
        if (uri.equals(Uri.EMPTY)) {
            target.setSummary(getString(defaultString));
            return;
        }

        final String summary = ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                ? uri.getPath() : uri.toString();
        target.setSummary(summary);
    }

    private boolean isFileUri(final String path) {
        return path.charAt(0) == File.separatorChar || path.startsWith(ContentResolver.SCHEME_FILE);
    }

    private boolean hasInvalidPath(final String prefKey) {
        final String value = defaultPreferences.getString(prefKey, null);
        return value == null || value.isEmpty();
    }

    private void updatePathPickers(final boolean enabled) {
        prefPathVideo.setEnabled(enabled);
        prefPathAudio.setEnabled(enabled);
    }

    // FIXME: after releasing the old path, all downloads created on the folder becomes inaccessible
    private void forgetSAFTree(final Context context, final String oldPath) {
        if (IGNORE_RELEASE_ON_OLD_PATH) {
            return;
        }

        if (oldPath == null || oldPath.isEmpty() || isFileUri(oldPath)) {
            return;
        }

        try {
            final Uri uri = Uri.parse(oldPath);

            context.getContentResolver()
                    .releasePersistableUriPermission(uri, StoredDirectoryHelper.PERMISSION_FLAGS);
            context.revokeUriPermission(uri, StoredDirectoryHelper.PERMISSION_FLAGS);

            Log.i(TAG, "Revoke old path permissions success on " + oldPath);
        } catch (final Exception err) {
            Log.e(TAG, "Error revoking old path permissions on " + oldPath, err);
        }
    }

    private void showMessageDialog(@StringRes final int title, @StringRes final int message) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .show();
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull final Preference preference) {
        if (DEBUG) {
            Log.d(TAG, "onPreferenceTreeClick() called with: "
                    + "preference = [" + preference + "]");
        }

        final String key = preference.getKey();

        if (key.equals(storageUseSafPreference)) {
            if (!NewPipeSettings.useStorageAccessFramework(ctx)) {
                NewPipeSettings.saveDefaultVideoDownloadDirectory(ctx);
                NewPipeSettings.saveDefaultAudioDownloadDirectory(ctx);
            } else {
                defaultPreferences.edit().putString(downloadPathVideoPreference, null)
                        .putString(downloadPathAudioPreference, null).apply();
            }
            updatePreferencesSummary();
            return true;
        } else if (key.equals(downloadPathVideoPreference)) {
            launchDirectoryPicker(requestDownloadVideoPathLauncher);
        } else if (key.equals(downloadPathAudioPreference)) {
            launchDirectoryPicker(requestDownloadAudioPathLauncher);
        } else if (key.equals("library_retag")) {
            LibraryRetagDialog.show(ctx, libraryDisposables);
        } else if (key.equals(getString(R.string.library_download_key))) {
            onLibraryDownloadClicked();
        } else {
            return super.onPreferenceTreeClick(preference);
        }

        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLibraryDownloadSummary();
    }

    @Override
    public void onDestroy() {
        libraryDisposables.clear();
        super.onDestroy();
    }

    private void updateLibraryDownloadSummary() {
        final Preference pref = findPreference(getString(R.string.library_download_key));
        if (pref == null) {
            return;
        }
        final LibraryDownloadJob.State state = LibraryDownloadJob.savedState(ctx);
        pref.setSummary(state == null ? getString(R.string.library_download_summary)
                : LibraryDownloadJob.describe(state));
    }

    private void onLibraryDownloadClicked() {
        if (LibraryRetagJob.isRunning()) {
            showMessageDialog(R.string.library_retag_title, R.string.library_retag_busy);
            return;
        }
        final LibraryDownloadJob.State state = LibraryDownloadJob.savedState(ctx);
        if (LibraryDownloadJob.isRunning()) {
            final AlertDialog.Builder runningDialog = new AlertDialog.Builder(ctx)
                    .setTitle(R.string.library_download_title)
                    .setMessage(state == null ? "" : LibraryDownloadJob.describe(state))
                    .setPositiveButton(R.string.library_download_pause, (d, w) -> {
                        LibraryDownloadJob.pause(
                                getString(R.string.library_download_paused_by_user));
                        updateLibraryDownloadSummary();
                    })
                    .setNegativeButton(R.string.ok, null);
            if (state != null && state.count(LibraryDownloadJob.Status.FAILED) > 0) {
                runningDialog.setNeutralButton(R.string.library_download_view_failures,
                        (d, w) -> showLibraryDownloadFailures(state));
            }
            runningDialog.show();
            return;
        }
        if (state != null) {
            final int left = state.count(LibraryDownloadJob.Status.PENDING)
                    + state.count(LibraryDownloadJob.Status.ACTIVE);
            final boolean onlyFailedLeft = left == 0
                    && state.count(LibraryDownloadJob.Status.FAILED) > 0;
            final AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                    .setTitle(R.string.library_download_title)
                    .setMessage(LibraryDownloadJob.describe(state))
                    .setNeutralButton(R.string.library_download_start_over, (d, w) -> {
                        LibraryDownloadJob.discard(ctx);
                        planLibraryDownload();
                    })
                    .setNegativeButton(R.string.cancel, null);
            if (left > 0 || onlyFailedLeft) {
                builder.setPositiveButton(onlyFailedLeft ? R.string.library_download_retry_failed
                        : R.string.library_download_resume, (d, w) -> {
                    LibraryDownloadJob.resume(ctx, true);
                    updateLibraryDownloadSummary();
                });
            }
            if (state.count(LibraryDownloadJob.Status.FAILED) > 0) {
                builder.setNegativeButton(R.string.library_download_view_failures,
                        (d, w) -> showLibraryDownloadFailures(state));
            }
            builder.show();
            return;
        }
        planLibraryDownload();
    }

    private void planLibraryDownload() {
        final AlertDialog progress = new AlertDialog.Builder(ctx)
                .setMessage(R.string.library_download_collecting)
                .setCancelable(false)
                .show();
        libraryDisposables.add(LibraryDownloadJob.prepare(ctx).subscribe(prepared -> {
            progress.dismiss();
            confirmLibraryDownload(prepared);
        }, error -> {
            progress.dismiss();
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.library_download_title)
                    .setMessage(getString(R.string.library_download_error, error.getMessage()))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }));
    }

    private void showLibraryDownloadFailures(final LibraryDownloadJob.State state) {
        final StringBuilder report = new StringBuilder(LibraryDownloadJob.describe(state));
        report.append("\n\n").append(getString(R.string.library_download_failures_explanation));
        int number = 0;
        for (final LibraryDownloadJob.Item item : state.getItems()) {
            if (item.getStatus() != LibraryDownloadJob.Status.FAILED) {
                continue;
            }
            final var track = item.getPlanned().getTrack();
            report.append("\n\n").append(++number).append(". ")
                    .append(track.getTitle()).append(" — ").append(track.getArtist())
                    .append("\n").append(track.getUrl())
                    .append("\n").append(getString(R.string.library_download_failure_attempts,
                            item.getAttempts()))
                    .append("\n").append(item.getError() == null
                            ? getString(R.string.library_download_failure_unknown)
                            : item.getError());
        }
        final String text = report.toString();
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.library_download_view_failures)
                .setMessage(text)
                .setPositiveButton(R.string.library_download_copy_report, (d, w) -> {
                    final ClipboardManager clipboard =
                            (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                getString(R.string.library_download_view_failures), text));
                    }
                })
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    private void confirmLibraryDownload(final LibraryDownloadJob.Prepared prepared) {
        final int playlists = prepared.getLocalPlaylists() + prepared.getRemotePlaylists();
        String message = String.format(Locale.getDefault(),
                getString(R.string.library_download_confirm),
                playlists, prepared.getLocalPlaylists(), prepared.getRemotePlaylists(),
                prepared.getPlan().getTotalEntries(), prepared.getPlan().getTracks().size(),
                prepared.getPlan().getAlreadyHave().size(),
                prepared.getPlan().getToDownload().size());
        message += getString(R.string.library_download_scan,
                prepared.getScannedFiles(), prepared.getMatchedOnDisk());
        if (!prepared.getUnloadedPlaylists().isEmpty()) {
            message += getString(R.string.library_download_unloaded,
                    String.join(", ", prepared.getUnloadedPlaylists()));
        }
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.library_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.library_download_start, (d, w) -> {
                    LibraryDownloadJob.start(ctx, prepared);
                    updateLibraryDownloadSummary();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchDirectoryPicker(final ActivityResultLauncher<Intent> launcher) {
        NoFileManagerSafeGuard.launchSafe(
                launcher,
                StoredDirectoryHelper.getPicker(ctx),
                TAG,
                ctx
        );
    }

    private void requestDownloadVideoPathResult(final ActivityResult result) {
        requestDownloadPathResult(result, downloadPathVideoPreference);
    }

    private void requestDownloadAudioPathResult(final ActivityResult result) {
        requestDownloadPathResult(result, downloadPathAudioPreference);
    }

    private void requestDownloadPathResult(final ActivityResult result, final String key) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }

        Uri uri = null;
        if (result.getData() != null) {
            uri = result.getData().getData();
        }
        if (uri == null) {
            showMessageDialog(R.string.general_error, R.string.invalid_directory);
            return;
        }


        // revoke permissions on the old save path (required for SAF only)
        final Context context = requireContext();

        forgetSAFTree(context, defaultPreferences.getString(key, ""));

        if (!FilePickerActivityHelper.isOwnFileUri(context, uri)) {
            // steps to acquire the selected path:
            //     1. acquire permissions on the new save path
            //     2. save the new path, if step(2) was successful
            try {
                context.grantUriPermission(context.getPackageName(), uri,
                        StoredDirectoryHelper.PERMISSION_FLAGS);

                final StoredDirectoryHelper mainStorage =
                        new StoredDirectoryHelper(context, uri, null);
                Log.i(TAG, "Acquiring tree success from " + uri.toString());

                if (!mainStorage.canWrite()) {
                    throw new IOException("No write permissions on " + uri.toString());
                }
            } catch (final IOException err) {
                Log.e(TAG, "Error acquiring tree from " + uri.toString(), err);
                showMessageDialog(R.string.general_error, R.string.no_available_dir);
                return;
            }
        } else {
            final File target = Utils.getFileForUri(uri);
            if (!target.canWrite()) {
                showMessageDialog(R.string.download_to_sdcard_error_title,
                        R.string.download_to_sdcard_error_message);
                return;
            }
            uri = Uri.fromFile(target);
        }

        defaultPreferences.edit().putString(key, uri.toString()).apply();
        updatePreferencesSummary();
    }
}
