package com.termux.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        androidx.appcompat.widget.Toolbar toolbar = findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(android.graphics.Color.BLACK);
            toolbar.setTitleTextColor(android.graphics.Color.WHITE);
        }
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureTermuxAPIPreference(context);
                    configureTermuxFloatPreference(context);
                    configureTermuxTaskerPreference(context);
                    configureTermuxWidgetPreference(context);
                    configureSyncWorkshopPreference(context);
                    configureSetupWizardPreference(context);
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                    configureUpdateCheckPreference(context);
                }
            }.start();
        }

        private void configureUpdateCheckPreference(@NonNull Context context) {
            Preference updatePreference = findPreference("check_update");
            if (updatePreference != null) {
                updatePreference.setOnPreferenceClickListener(preference -> {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            android.widget.Toast.makeText(context, "Checking for updates...", android.widget.Toast.LENGTH_SHORT).show();
                            com.termux.app.utils.UpdateChecker.checkForUpdates(context);
                        });
                    }
                    return true;
                });
            }
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureSetupWizardPreference(@NonNull Context context) {
            Preference setupPreference = findPreference("setup_wizard");
            if (setupPreference != null) {
                setupPreference.setOnPreferenceClickListener(preference -> {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                            getActivity().startActivity(new android.content.Intent(
                                getActivity(),
                                com.termux.app.setup.SetupWizardActivity.class)));
                    }
                    return true;
                });
            }
        }

        private void configureSyncWorkshopPreference(@NonNull Context context) {            Preference syncWorkshopPreference = findPreference("sync_workshop");
            if (syncWorkshopPreference != null) {
                syncWorkshopPreference.setOnPreferenceClickListener(preference -> {
                    new Thread(() -> {
                        try {
                            String androidDir = "/storage/emulated/0/NOVUS IDE/";
                            String ubuntuDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/ubuntu/root/NOVUS IDE/";

                            // Run first sync: Android -> Ubuntu
                            String[] cmd1 = {"sh", "-c", "mkdir -p \"" + ubuntuDir + "\" && rsync -auv \"" + androidDir + "\" \"" + ubuntuDir + "\""};
                            Process p1 = Runtime.getRuntime().exec(cmd1);
                            p1.waitFor();

                            // Run second sync: Ubuntu -> Android
                            String[] cmd2 = {"sh", "-c", "mkdir -p \"" + androidDir + "\" && rsync -auv \"" + ubuntuDir + "\" \"" + androidDir + "\""};
                            Process p2 = Runtime.getRuntime().exec(cmd2);
                            p2.waitFor();

                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> 
                                    android.widget.Toast.makeText(context, "Workshop Sync Completed Successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                );
                            }
                        } catch (Exception e) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> 
                                    android.widget.Toast.makeText(context, "Workshop Sync Failed!", android.widget.Toast.LENGTH_SHORT).show()
                                );
                            }
                            com.termux.shared.logger.Logger.logStackTraceWithMessage("SettingsActivity", "Error during workshop sync", e);
                        }
                    }).start();
                    return true;
                });
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Termux isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                    return true;
                });
            }
        }
    }

}
