package com.suyashsrijan.forcedoze;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nanotasks.BackgroundWork;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

import de.cketti.library.changelog.ChangeLog;
import eu.chainfire.libsuperuser.Shell;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private int mLastExitCode = -1;
    private boolean mCommandRunning = false;
    private HandlerThread mCallbackThread = null;
    private static Shell.Interactive rootSession;
    private static Shell.Interactive nonRootSession;
    public static String TAG = "ForceDoze";
    SharedPreferences settings;
    SharedPreferences.Editor editor;
    boolean isDozeEnabledByOEM = true;
    boolean isSuAvailable = false;
    boolean isDozeDisabled = false;
    boolean serviceEnabled = false;
    boolean isDumpPermGranted = false;
    boolean isWriteSecureSettingsPermGranted = false;
    boolean ignoreLockscreenTimeout = true;
    boolean showDonateDevDialog = false;
    SwitchCompat toggleForceDozeSwitch;
    MaterialDialog progressDialog = null;
    TextView textViewStatus;
    CoordinatorLayout coordinatorLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setElevation(0.0f);
        }

        CustomTabs.with(getApplicationContext()).warm();
        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isDozeEnabledByOEM = Utils.checkForAutoPowerModesFlag();
        showDonateDevDialog = settings.getBoolean("showDonateDevDialog1", false);
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        isDozeDisabled = settings.getBoolean("isDozeDisabled", false);
        isSuAvailable = settings.getBoolean("isSuAvailable", false);
        ignoreLockscreenTimeout = settings.getBoolean("ignoreLockscreenTimeout", true);
        toggleForceDozeSwitch = (SwitchCompat) findViewById(R.id.switch1);
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());
        isWriteSecureSettingsPermGranted = Utils.isSecureSettingsPermissionGranted(getApplicationContext());
        textViewStatus = (TextView) findViewById(R.id.textView2);

        toggleForceDozeSwitch.setOnCheckedChangeListener(null);

        if (serviceEnabled) {
            textViewStatus.setText(R.string.service_active);
            toggleForceDozeSwitch.setChecked(true);
        } else {
            textViewStatus.setText(R.string.service_inactive);
            toggleForceDozeSwitch.setChecked(false);
        }

        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        if (!Utils.isDeviceRunningOnN() && isDumpPermGranted) {
            Log.i(TAG, "android.permission.DUMP already granted and user not on Nougat, skipping SU check");
            doAfterSuCheckSetup();
        } else if (Utils.isDeviceRunningOnN() && isDumpPermGranted && isWriteSecureSettingsPermGranted) {
            Log.i(TAG, "android.permission.DUMP & android.permission.WRITE_SECURE_SETTINGS already granted and user on Nougat, skipping SU check");
            doAfterSuCheckSetup();
        } else {
            progressDialog = new MaterialDialog.Builder(this)
                    .title(R.string.please_wait_text)
                    .autoDismiss(false)
                    .cancelable(false)
                    .content(R.string.requesting_su_access_text)
                    .progress(true, 0)
                    .show();
            Log.i(TAG, "Check if SU is available, and request SU permission if it is");
            Tasks.executeInBackground(MainActivity.this, new BackgroundWork<Boolean>() {
                @Override
                public Boolean doInBackground() throws Exception {
                    if (rootSession != null) {
                        if (rootSession.isRunning()) {
                            return true;
                        } else {
                            dispose();
                        }
                    }

                    mCallbackThread = new HandlerThread("SU callback");
                    mCallbackThread.start();

                    mCommandRunning = true;
                    rootSession = new Shell.Builder().useSU()
                            .setHandler(new Handler(mCallbackThread.getLooper()))
                            .setOnSTDERRLineListener(mStderrListener)
                            .open(mOpenListener);

                    waitForCommandFinished();

                    if (mLastExitCode != Shell.OnCommandResultListener.SHELL_RUNNING) {
                        dispose();
                        return false;
                    }

                    return true;
                }
            }, new Completion<Boolean>() {
                @Override
                public void onSuccess(Context context, Boolean result) {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                    }
                    isSuAvailable = result;
                    Log.i(TAG, "SU available: " + Boolean.toString(result));
                    if (isSuAvailable) {
                        Log.i(TAG, "Phone is rooted and SU permission granted");
                        editor = settings.edit();
                        editor.putBoolean("isSuAvailable", true);
                        editor.apply();
                        if (!Utils.isDumpPermissionGranted(getApplicationContext())) {
                                Log.i(TAG, "Granting android.permission.DUMP to com.suyashsrijan.forcedoze");
                                executeCommand("pm grant com.suyashsrijan.forcedoze android.permission.DUMP");
                        }
                        if (!Utils.isReadPhoneStatePermissionGranted(getApplicationContext())) {
                            Log.i(TAG, "Granting android.permission.READ_PHONE_STATE to com.suyashsrijan.forcedoze");
                            executeCommand("pm grant com.suyashsrijan.forcedoze android.permission.READ_PHONE_STATE");
                        }
                        if (!Utils.isSecureSettingsPermissionGranted(getApplicationContext()) && Utils.isDeviceRunningOnN()) {
                            Log.i(TAG, "Granting android.permission.WRITE_SECURE_SETTINGS to com.suyashsrijan.forcedoze");
                            executeCommand("pm grant com.suyashsrijan.forcedoze android.permission.WRITE_SECURE_SETTINGS");
                        }
                        if (serviceEnabled) {
                            toggleForceDozeSwitch.setChecked(true);
                            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                                Log.i(TAG, "Starting ForceDozeService");
                                startService(new Intent(context, ForceDozeService.class));
                            } else {
                                Log.i(TAG, "Service already running");
                            }
                        } else {
                            Log.i(TAG, "Service not enabled");
                        }

                        ChangeLog cl = new ChangeLog(MainActivity.this);
                        if (cl.isFirstRun()) {
                            cl.getFullLogDialog().show();
                        }
                    } else {
                        Log.i(TAG, "SU permission denied or not available");
                        toggleForceDozeSwitch.setChecked(false);
                        toggleForceDozeSwitch.setEnabled(false);
                        textViewStatus.setText(R.string.service_disabled);
                        editor = settings.edit();
                        editor.putBoolean("isSuAvailable", false);
                        editor.apply();
                        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppCompatAlertDialogStyle);
                        builder.setTitle(getString(R.string.error_text));
                        builder.setMessage(getString(R.string.root_workaround_text));
                        builder.setPositiveButton(getString(R.string.close_button_text), null);
                        builder.setNegativeButton(getString(R.string.root_workaround_button_text), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                dialogInterface.dismiss();
                                showRootWorkaroundInstructions();
                            }
                        });
                        builder.show();
                    }
                }

                @Override
                public void onError(Context context, Exception e) {
                    Log.e(TAG, "Error querying SU: " + e.getMessage());
                }
            });
        }

        if (Utils.isLockscreenTimeoutValueTooHigh(getContentResolver())) {
            if (!ignoreLockscreenTimeout) {
                coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
                Snackbar.make(coordinatorLayout, R.string.lockscreen_timeout_snackbar_text, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.more_info_text, new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                showLockScreenTimeoutInfoDialog();
                            }
                        })
                        .setActionTextColor(Color.RED)
                        .show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rootSession != null) {
            rootSession.close();
            rootSession = null;
        }
        if (nonRootSession != null) {
            nonRootSession.close();
            nonRootSession = null;
        }
    }

    public void doAfterSuCheckSetup() {
        if (serviceEnabled) {
            toggleForceDozeSwitch.setChecked(true);
            textViewStatus.setText(R.string.service_active);
            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                Log.i(TAG, "Starting ForceDozeService");
                startService(new Intent(this, ForceDozeService.class));
            } else {
                Log.i(TAG, "Service already running");
            }
            if (isSuAvailable) {
                executeCommand("chmod 664 /data/data/com.suyashsrijan.forcedoze/shared_prefs/com.suyashsrijan.forcedoze_preferences.xml");
                executeCommand("chmod 755 /data/data/com.suyashsrijan.forcedoze/shared_prefs");
            }
        } else {
            textViewStatus.setText(R.string.service_inactive);
            Log.i(TAG, "Service not enabled");
        }
        ChangeLog cl = new ChangeLog(this);
        if (cl.isFirstRun()) {
            cl.getFullLogDialog().show();
        } else {
            if (showDonateDevDialog) {
            Log.i(TAG, "Skipping donate dialog");
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());

        if (isDozeEnabledByOEM || Utils.isDeviceRunningOnN()) {
            menu.getItem(2).setVisible(false);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_toggle_doze:
                showEnableDozeOnUnsupportedDeviceDialog();
                break;
            case R.id.action_doze_batterystats:
                startActivity(new Intent(MainActivity.this, DozeBatteryStatsActivity.class));
                break;
            case R.id.action_app_settings:
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                break;
            case R.id.action_show_doze_tunables:
                showDozeTunablesActivity();
                break;

        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        if (b) {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", true);
            editor.apply();
            serviceEnabled = true;
            textViewStatus.setText(R.string.service_active);
            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                Log.i(TAG, "Enabling ForceDoze");
                startService(new Intent(MainActivity.this, ForceDozeService.class));
            }
            showForceDozeActiveDialog();
        } else {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", false);
            editor.apply();
            serviceEnabled = false;
            textViewStatus.setText(R.string.service_inactive);
            if (Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                Log.i(TAG, "Disabling ForceDoze");
                stopService(new Intent(MainActivity.this, ForceDozeService.class));
            }
        }
    }

    public void showDozeTunablesActivity() {
        if (serviceEnabled) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
            builder.setTitle("Warning");
            builder.setMessage("Modifying Doze tunables will turn off ForceDoze, as ForceDoze overrides Doze tunables by default in order to put your device immediately into Doze mode.\n\nAre you sure you want to continue?");
            builder.setPositiveButton(getString(R.string.yes_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
                    toggleForceDozeSwitch.setChecked(false);
                    startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
                }
            });
            builder.setNegativeButton(getString(R.string.no_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
                }
            });
            builder.show();
        } else {
            startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
        }
    }

    public void openDonatePage() {
        CustomTabs.with(getApplicationContext())
                .setStyle(new CustomTabs.Style(getApplicationContext())
                        .setShowTitle(true)
                        .setExitAnimation(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                        .setToolbarColor(R.color.colorPrimary))
                .openUrl("https://www.paypal.me/suyashsrijan", this);
    }

    public void showEnableDozeOnUnsupportedDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.doze_unsupported_more_info_title));
        builder.setMessage(getString(R.string.doze_unsupported_more_info));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.setNegativeButton(getString(R.string.enable_doze_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                executeCommand("setprop persist.sys.doze_powersave true");
                if (Utils.isDeviceRunningOnN()) {
                    executeCommand("dumpsys deviceidle disable all");
                    executeCommand("dumpsys deviceidle enable all");
                } else {
                    executeCommand("dumpsys deviceidle disable");
                    executeCommand("dumpsys deviceidle enable");
                }
                if (Utils.isXposedInstalled(getApplicationContext())) {
                    showEnableXposedModuleDialog();
                }
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showEnableXposedModuleDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.xposed_detected_dialog_title));
        builder.setMessage(getString(R.string.xposed_detected_dialog_text));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showRootWorkaroundInstructions() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.no_root_workaround_dialog_title));
        builder.setMessage(getString(R.string.no_root_workaround_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        builder.setNegativeButton(getString(R.string.share_command_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, "adb -d shell pm grant com.suyashsrijan.forcedoze android.permission.DUMP");
                sendIntent.setType("text/plain");
                startActivity(sendIntent);

            }
        });
        builder.show();
    }

    public void showLockScreenTimeoutInfoDialog() {
        float lockscreenTimeout = Utils.getLockscreenTimeoutValue(getContentResolver());
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.lockscreen_timeout_dialog_title));
        builder.setMessage(getString(R.string.lockscreen_timeout_dialog_text_p1) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p2) +
                getString(R.string.lockscreen_timeout_dialog_text_p3) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p4) +
                getString(R.string.lockscreen_timeout_dialog_text_p5) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p6));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        builder.setNegativeButton(getString(R.string.open_security_settings_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
                Intent securitySettingsIntent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                startActivity(securitySettingsIntent);

            }
        });
        builder.show();
    }

    public void showMoreInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.more_info_text));
        builder.setMessage(getString(R.string.how_doze_works_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showForceDozeActiveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.forcedoze_active_dialog_title));
        builder.setMessage(getString(R.string.forcedoze_active_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showDonateDevDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle);
        builder.setTitle(getString(R.string.donate_dialog_title));
        builder.setMessage(getString(R.string.donate_dialog_text));
        builder.setPositiveButton(getString(R.string.donate_dialog_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                editor = settings.edit();
                editor.putBoolean("showDonateDevDialog1", false);
                editor.apply();
                dialogInterface.dismiss();
                openDonatePage();
            }
        });
        builder.setNegativeButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                editor = settings.edit();
                editor.putBoolean("showDonateDevDialog1", false);
                editor.apply();
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }



    private final Shell.OnCommandResultListener mOpenListener = new Shell.OnCommandResultListener() {
        @Override
        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
            mStdoutListener.onCommandResult(commandCode, exitCode);
        }
    };

    private final Shell.OnCommandLineListener mStdoutListener = new Shell.OnCommandLineListener() {
        public void onLine(String line) {
            Log.i(TAG, line);
        }

        @Override
        public void onCommandResult(int commandCode, int exitCode) {
            mLastExitCode = exitCode;
            synchronized (mCallbackThread) {
                mCommandRunning = false;
                mCallbackThread.notifyAll();
            }
        }
    };

    private final Shell.OnCommandLineListener mStderrListener = new Shell.OnCommandLineListener() {
        @Override
        public void onLine(String line) {
            Log.i(TAG, line);
        }

        @Override
        public void onCommandResult(int commandCode, int exitCode) {

        }
    };

    private void waitForCommandFinished() {
        synchronized (mCallbackThread) {
            while (mCommandRunning) {
                try {
                    mCallbackThread.wait();
                } catch (Exception e)  {
                    if (e instanceof InterruptedException) {
                        Log.i(TAG, "InterruptedException occurred while waiting for command to finish");
                        e.printStackTrace();
                    } else if (e instanceof NullPointerException) {
                        Log.i(TAG, "NPE occurred while waiting for command to finish");
                        e.printStackTrace();
                    }
                }
            }
        }

        if (mLastExitCode == Shell.OnCommandResultListener.WATCHDOG_EXIT || mLastExitCode == Shell.OnCommandResultListener.SHELL_DIED) {
            dispose();
        }
    }

    public synchronized void dispose() {
        if (rootSession == null) {
            return;
        }

        try {
            rootSession.close();
        } catch (Exception ignored) {
        }
        rootSession = null;

        mCallbackThread.quit();
        mCallbackThread = null;
    }

    public void executeCommand(final String command) {
        if (isSuAvailable) {
            executeCommandWithRoot(command);
        } else {
            executeCommandWithoutRoot(command);
        }
    }

    public void executeCommandWithRoot(final String command) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (rootSession != null) {
                    rootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            printShellOutput(output);
                        }
                    });
                } else {
                    rootSession = new Shell.Builder().
                            useSU().
                            setWantSTDERR(true).
                            setWatchdogTimeout(5).
                            setMinimalLogging(true).
                            open(new Shell.OnCommandResultListener() {
                                @Override
                                public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                    if (exitCode != Shell.OnCommandResultListener.SHELL_RUNNING) {
                                        Log.i(TAG, "Error opening root shell: exitCode " + exitCode);
                                    } else {
                                        rootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                                            @Override
                                            public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                                printShellOutput(output);
                                            }
                                        });
                                    }
                                }
                            });
                }
            }
        });
    }

    public void executeCommandWithoutRoot(final String command) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (nonRootSession != null) {
                    nonRootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            printShellOutput(output);
                        }
                    });
                } else {
                    nonRootSession = new Shell.Builder().
                            useSH().
                            setWantSTDERR(true).
                            setWatchdogTimeout(5).
                            setMinimalLogging(true).
                            open(new Shell.OnCommandResultListener() {
                                @Override
                                public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                    if (exitCode != Shell.OnCommandResultListener.SHELL_RUNNING) {
                                        Log.i(TAG, "Error opening shell: exitCode " + exitCode);
                                    } else {
                                        nonRootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                                            @Override
                                            public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                                printShellOutput(output);
                                            }
                                        });
                                    }
                                }
                            });
                }
            }
        });
    }

    public void printShellOutput(List<String> output) {
        if (!output.isEmpty()) {
            for (String s : output) {
                Log.i(TAG, s);
            }
        }
    }
}
