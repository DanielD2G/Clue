package com.clue.tuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ClueScreen;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CLUE: finds the highest stable bitrate for one PC on the current network.
 *
 * Each step launches Game in test mode against the host's "Clue Bitrate Test" app (full-screen
 * GPU noise the encoder cannot compress) and gets StepMetrics back as the activity result.
 * BitrateSearch picks the steps, StepVerdict judges them, TunerStore keeps the result.
 * Back at any point cancels everything, quits the test app on the host and saves nothing.
 */
public class BitrateTunerActivity extends Activity {
    public static final String EXTRA_PC_UUID = "uuid";
    /** Exact name of the host app installed by tools/host/install-bitrate-test.ps1. */
    public static final String TEST_APP_NAME = "Clue Bitrate Test";

    private static final int REQ_STEP = 0xC1E;
    private static final long STEP_GAP_MS = 1500;
    private static final int FALLBACK_KBPS = 10000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String uuid;
    private String network;
    private ComputerDetails computer;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private NvApp testApp;

    private final BitrateSearch search = new BitrateSearch();
    private double baselineRttMs = -1;
    private int decoderMaxKbps;
    private int stepCount;
    private int currentKbps;
    private boolean firstStepInterrupted;
    private boolean launchedTestApp;
    private boolean ended; // cancelled or finished: no more steps
    private final List<String> rows = new ArrayList<>();

    private TextView status;
    private ProgressBar progress;
    private LinearLayout stepList;
    private ScrollView stepScroll;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    (ComputerManagerService.ComputerManagerBinder) binder;
            new Thread(() -> {
                localBinder.waitForReady();
                managerBinder = localBinder;
                prepare(localBinder);
            }, "Clue tuner prepare").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            // Recreated behind a running step: the search state is gone, start over from the menu
            finish();
            return;
        }

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_bitrate_tuner);
        UiHelper.notifyNewRootView(this);
        ClueScreen.setupHeader(this, getString(R.string.tuner_title));
        ClueScreen.applyImmersive(this);
        // The run takes minutes without touching the screen; a timeout between steps would stop it
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        status = findViewById(R.id.tuner_status);
        progress = findViewById(R.id.tuner_progress);
        stepList = findViewById(R.id.tuner_steps);
        stepScroll = findViewById(R.id.tuner_steps_scroll);

        uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
        if (uuid == null) {
            finish();
            return;
        }
        network = TunerStore.networkKey(this);
        LimeLog.info("Clue tuner: pc " + uuid + " network " + network);

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ClueScreen.applyImmersive(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (managerBinder != null || uuid != null) {
            try {
                unbindService(serviceConnection);
            } catch (IllegalArgumentException ignored) {
                // never bound
            }
        }
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    // ---- Preparation (background thread) ----

    private void prepare(ComputerManagerService.ComputerManagerBinder binder) {
        ComputerDetails details = binder.getComputer(uuid);
        if (details == null || details.activeAddress == null || details.state == ComputerDetails.State.OFFLINE) {
            runOnUiThread(() -> showFatal(null, getString(R.string.tuner_pc_offline)));
            return;
        }
        computer = details;
        try {
            NvHTTP http = newHttp();
            NvApp found = null;
            for (NvApp app : http.getAppList()) {
                if (TEST_APP_NAME.equals(app.getAppName())) {
                    found = app;
                    break;
                }
            }
            if (found == null) {
                runOnUiThread(() -> showFatal(getString(R.string.tuner_no_app_title), getString(R.string.tuner_no_app_msg)));
                return;
            }
            testApp = found;

            int running = http.getCurrentGame(http.getServerInfo(true));
            if (running != 0 && running != testApp.getAppId()) {
                String runningName = "?";
                for (NvApp app : http.getAppList()) {
                    if (app.getAppId() == running) {
                        runningName = app.getAppName();
                        break;
                    }
                }
                final String nameForDialog = runningName;
                runOnUiThread(() -> askQuitOtherApp(nameForDialog));
                return;
            }
        } catch (Exception e) {
            LimeLog.warning("Clue tuner: cannot read the app list: " + e);
            runOnUiThread(() -> showFatal(null, getString(R.string.tuner_pc_offline)));
            return;
        }
        runOnUiThread(this::nextStep);
    }

    private NvHTTP newHttp() throws java.io.IOException {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                managerBinder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(this));
    }

    private void askQuitOtherApp(String runningName) {
        if (isFinishing() || ended) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.tuner_other_app_title)
                .setMessage(getString(R.string.tuner_other_app_msg, runningName))
                .setPositiveButton(R.string.tuner_other_app_quit, (d, w) -> new Thread(() -> {
                    try {
                        newHttp().quitApp();
                    } catch (Exception e) {
                        LimeLog.warning("Clue tuner: quit failed: " + e);
                    }
                    handler.postDelayed(this::nextStep, STEP_GAP_MS);
                }, "Clue tuner quit").start())
                .setNegativeButton(R.string.tuner_cancel, (d, w) -> cancel())
                .setOnCancelListener(d -> cancel())
                .show();
    }

    // ---- Steps ----

    private void nextStep() {
        if (ended || isFinishing()) {
            return;
        }
        int kbps = search.nextKbps();
        if (kbps < 0) {
            done();
            return;
        }
        currentKbps = kbps;
        stepCount++;
        status.setText(getString(R.string.tuner_status_step, stepCount, kbps / 1000));

        Intent i = ServerHelper.createStartIntent(this, testApp, computer, managerBinder);
        i.putExtra(Game.EXTRA_TUNE_KBPS, kbps);
        launchedTestApp = true;
        startActivityForResult(i, REQ_STEP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_STEP || ended) {
            return;
        }
        if (resultCode != RESULT_OK) {
            // Back (or leaving the app) during a step cancels the whole test
            cancel();
            return;
        }

        StepMetrics m = StepMetrics.readFrom(data, currentKbps);
        StepVerdict.Result v = StepVerdict.evaluate(m, baselineRttMs);
        if (stepCount == 1) {
            if (!m.interrupted) {
                baselineRttMs = m.rttP95Ms;
            } else {
                firstStepInterrupted = true;
            }
        }
        if (m.decoderMaxKbps > 0) {
            decoderMaxKbps = m.decoderMaxKbps;
        }
        search.report(currentKbps, v.stable);
        addRow(currentKbps, v);
        LimeLog.info(String.format(Locale.ROOT, "Clue tuner: %d kbps %s %s (recv %d lost %d pres %d fps %.1f dec %.1f rtt %.0f)",
                currentKbps, v.code, v.reason, m.framesReceived, m.framesLost, m.framesPresented,
                m.fpsReceived, m.decodeP95Ms, m.rttP95Ms));

        handler.postDelayed(this::nextStep, STEP_GAP_MS);
    }

    private void addRow(int kbps, StepVerdict.Result v) {
        String text;
        if (v.stable) {
            text = "✓  " + getString(R.string.tuner_step_ok, kbps / 1000);
        } else {
            text = "✗  " + getString(R.string.tuner_step_fail, kbps / 1000, reasonText(v));
        }
        rows.add(text);

        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        row.setTextColor(getResources().getColor(v.stable ? R.color.clue_launcher_text : R.color.clue_launcher_text_dim));
        row.setBackgroundResource(R.drawable.clue_pref_row);
        int padH = dp(20), padV = dp(14);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        stepList.addView(row, lp);
        stepScroll.post(() -> stepScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String reasonText(StepVerdict.Result v) {
        switch (v.code) {
            case INTERRUPTED: return getString(R.string.tuner_reason_interrupted);
            case NO_FRAMES: return getString(R.string.tuner_reason_no_frames);
            case LOSS: return getString(R.string.tuner_reason_loss, v.reason);
            case FPS: return getString(R.string.tuner_reason_fps, v.reason);
            case DECODE: return getString(R.string.tuner_reason_decode, v.reason);
            case UNPRESENTED: return getString(R.string.tuner_reason_unpresented, v.reason);
            case RTT: return getString(R.string.tuner_reason_rtt, v.reason);
            default: return v.reason;
        }
    }

    // ---- End ----

    private void done() {
        ended = true;
        quitTestAppAsync();
        progress.setIndeterminate(false);
        progress.setProgress(100);
        status.setText(R.string.tuner_status_done);

        int best = search.bestStableKbps();
        if (best <= 0) {
            if (firstStepInterrupted) {
                showFatal(getString(R.string.tuner_first_interrupted_title), getString(R.string.tuner_first_interrupted_msg));
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.tuner_fail_first_title)
                    .setMessage(getString(R.string.tuner_fail_first_msg) + "\n\n" + joinRows())
                    .setPositiveButton(R.string.tuner_use_10, (d, w) -> apply(FALLBACK_KBPS))
                    .setNegativeButton(R.string.tuner_cancel, (d, w) -> declineResult())
                    .setOnCancelListener(d -> declineResult())
                    .show();
            return;
        }

        final int suggested = BitrateSearch.suggest(best, decoderMaxKbps);
        StringBuilder msg = new StringBuilder(getString(R.string.tuner_result_best, best / 1000));
        if (decoderMaxKbps >= 5000 && (long) best * 85 / 100 > decoderMaxKbps) { // same rule as suggest()
            msg.append('\n').append(getString(R.string.tuner_result_capped, decoderMaxKbps / 1000));
        }
        msg.append("\n\n").append(joinRows());
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.tuner_result_title, suggested / 1000))
                .setMessage(msg)
                .setPositiveButton(R.string.tuner_apply, (d, w) -> apply(suggested))
                .setNegativeButton(R.string.tuner_cancel, (d, w) -> declineResult())
                .setOnCancelListener(d -> declineResult())
                .show();
    }

    private void apply(int kbps) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, kbps)
                .apply();
        new TunerStore(this).save(uuid, network, kbps, joinRows());
        Toast.makeText(this, getString(R.string.tuner_applied, kbps / 1000), Toast.LENGTH_LONG).show();
        LimeLog.info("Clue tuner: applied " + kbps + " kbps for " + uuid + " on " + network);
        finish();
    }

    /** The test ran but the user kept their bitrate: don't offer the automatic test again here. */
    private void declineResult() {
        new TunerStore(this).markSkipped(uuid, network);
        finish();
    }

    private void cancel() {
        if (!ended) {
            ended = true;
            quitTestAppAsync();
            if (status != null) {
                status.setText(R.string.tuner_status_cancelled);
            }
            LimeLog.info("Clue tuner: cancelled");
        }
        finish();
    }

    /** Never leave the noise app running on the PC. Waits for Game's disconnect to go out first. */
    private void quitTestAppAsync() {
        if (!launchedTestApp || computer == null || managerBinder == null) {
            return;
        }
        launchedTestApp = false;
        final Context app = getApplicationContext();
        final ComputerDetails pc = computer;
        final String uniqueId = managerBinder.getUniqueId();
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort,
                        uniqueId, pc.serverCert, PlatformBinding.getCryptoProvider(app));
                LimeLog.info("Clue tuner: quit test app " + (http.quitApp() ? "ok" : "failed"));
            } catch (Exception e) {
                LimeLog.warning("Clue tuner: quit test app failed: " + e);
            }
        }, "Clue tuner quit").start();
    }

    private void showFatal(String title, String message) {
        if (isFinishing()) {
            return;
        }
        ended = true;
        quitTestAppAsync();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish());
        if (title != null) {
            b.setTitle(title);
        }
        b.show();
    }

    private String joinRows() {
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r);
        }
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
