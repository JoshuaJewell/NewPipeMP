package org.schabi.newpipe.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.adaptive.ui.AdaptiveShuffleDebugActivity;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;
import org.schabi.newpipe.util.StreamMetadataRepair;
import org.schabi.newpipe.util.stall.StallWatchdog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import java.util.Optional;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class DebugSettingsFragment extends BasePreferenceFragment {
    private static final String DUMMY = "Dummy";

    private final CompositeDisposable disposables = new CompositeDisposable();

    // Saves every stall report, newest first, into one text file the user picks.
    private final ActivityResultLauncher<String> saveStallReports = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::writeStallReports);

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final Preference allowHeapDumpingPreference =
                requirePreference(R.string.allow_heap_dumping_key);
        final Preference showMemoryLeaksPreference =
                requirePreference(R.string.show_memory_leaks_key);
        final Preference checkNewStreamsPreference =
                requirePreference(R.string.check_new_streams_key);
        final Preference crashTheAppPreference =
                requirePreference(R.string.crash_the_app_key);
        final Preference showErrorSnackbarPreference =
                requirePreference(R.string.show_error_snackbar_key);
        final Preference createErrorNotificationPreference =
                requirePreference(R.string.create_error_notification_key);
        final Preference repairStreamMetadataPreference =
                requirePreference(R.string.repair_stream_metadata_key);


        final Optional<DebugSettingsBVDLeakCanaryAPI> optBVLeakCanary = getBVDLeakCanary();

        allowHeapDumpingPreference.setEnabled(optBVLeakCanary.isPresent());
        showMemoryLeaksPreference.setEnabled(optBVLeakCanary.isPresent());

        if (optBVLeakCanary.isPresent()) {
            final DebugSettingsBVDLeakCanaryAPI pdLeakCanary = optBVLeakCanary.get();

            showMemoryLeaksPreference.setOnPreferenceClickListener(preference -> {
                startActivity(pdLeakCanary.getNewLeakDisplayActivityIntent());
                return true;
            });
        } else {
            allowHeapDumpingPreference.setSummary(R.string.leak_canary_not_available);
            showMemoryLeaksPreference.setSummary(R.string.leak_canary_not_available);
        }

        checkNewStreamsPreference.setOnPreferenceClickListener(preference -> {
            NotificationWorker.runNow(preference.getContext());
            return true;
        });

        crashTheAppPreference.setOnPreferenceClickListener(preference -> {
            throw new RuntimeException(DUMMY);
        });

        showErrorSnackbarPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.showUiErrorSnackbar(DebugSettingsFragment.this,
                    DUMMY, new RuntimeException(DUMMY));
            return true;
        });

        createErrorNotificationPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.createNotification(requireContext(),
                    new ErrorInfo(new RuntimeException(DUMMY), UserAction.UI_ERROR, DUMMY));
            return true;
        });

        final Preference adaptiveShuffleDebugPreference =
                requirePreference(R.string.adaptive_shuffle_debug_screen_key);
        adaptiveShuffleDebugPreference.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), AdaptiveShuffleDebugActivity.class));
            return true;
        });

        final Preference stallReportsPreference =
                requirePreference(R.string.stall_reports_key);
        updateStallReportsSummary(stallReportsPreference);
        stallReportsPreference.setOnPreferenceClickListener(preference -> {
            final List<File> reports = StallWatchdog.INSTANCE.reports(requireContext());
            if (reports.isEmpty()) {
                Toast.makeText(requireContext(), R.string.stall_reports_none,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.stall_reports_title)
                    .setMessage(getString(R.string.stall_reports_dialog, reports.size()))
                    .setPositiveButton(R.string.stall_reports_save, (d, w) ->
                            saveStallReports.launch("newpipe-stall-reports.txt"))
                    .setNeutralButton(R.string.stall_reports_delete, (d, w) -> {
                        for (final File f : reports) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                        updateStallReportsSummary(preference);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        repairStreamMetadataPreference.setOnPreferenceClickListener(preference -> {
            Toast.makeText(requireContext(), "Starting metadata repair...",
                    Toast.LENGTH_SHORT).show();

            disposables.add(
                StreamMetadataRepair.repairAllCorruptedEntries(requireContext())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        repairedCount -> Toast.makeText(requireContext(),
                            "Repaired " + repairedCount + " corrupted entries",
                            Toast.LENGTH_LONG).show(),
                        error -> ErrorUtil.showUiErrorSnackbar(
                            DebugSettingsFragment.this,
                            "Repairing stream metadata",
                            error)
                    )
            );
            return true;
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.dispose();
    }

    private void updateStallReportsSummary(final Preference preference) {
        final List<File> reports = StallWatchdog.INSTANCE.reports(requireContext());
        preference.setSummary(reports.isEmpty()
                ? getString(R.string.stall_reports_summary_none)
                : getString(R.string.stall_reports_summary, reports.size(),
                        reports.get(0).getName()));
    }

    private void writeStallReports(final Uri uri) {
        if (uri == null) {
            return;
        }
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                return;
            }
            for (final File f : StallWatchdog.INSTANCE.reports(requireContext())) {
                out.write(("\n\n##### " + f.getName() + " #####\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(f)) {
                    final byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
            }
            Toast.makeText(requireContext(), R.string.stall_reports_saved,
                    Toast.LENGTH_SHORT).show();
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Saving stall reports", e);
        }
    }

    /**
     * Tries to find the {@link DebugSettingsBVDLeakCanaryAPI#IMPL_CLASS} and loads it if available.
     * @return An {@link Optional} which is empty if the implementation class couldn't be loaded.
     */
    private Optional<DebugSettingsBVDLeakCanaryAPI> getBVDLeakCanary() {
        try {
            // Try to find the implementation of the LeakCanary API
            return Optional.of((DebugSettingsBVDLeakCanaryAPI)
                    Class.forName(DebugSettingsBVDLeakCanaryAPI.IMPL_CLASS)
                            .getDeclaredConstructor()
                            .newInstance());
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Build variant dependent (BVD) leak canary API for this fragment.
     * Why is LeakCanary not used directly? Because it can't be assured
     */
    public interface DebugSettingsBVDLeakCanaryAPI {
        String IMPL_CLASS =
                "org.schabi.newpipe.settings.DebugSettingsBVDLeakCanary";

        Intent getNewLeakDisplayActivityIntent();
    }
}
