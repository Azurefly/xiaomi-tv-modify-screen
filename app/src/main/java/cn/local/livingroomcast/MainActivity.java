/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

import cn.local.livingroomcast.core.Dlna;
import cn.local.livingroomcast.core.QualityCatalog;

/**
 * Single screen, four layers: standby, error, HUD and a transient connecting pill.
 * All styling lives in res/ (colors, dimens, styles) so the visual language can be
 * swapped without touching behaviour. See design/README.md for the source of truth.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final long HUD_TIMEOUT_MS = 2000;
    private static final long CONNECTING_HINT_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ReceiverService service;
    private boolean bound, video;

    private PlayerView playerView;
    private View waiting, errorPanel, connecting, hud;
    private TextView waitingStatus, address, received, waitingError, connectingQuality, errorSubtitle;
    private TextView hudTitle, hudMeta, hudPosition, hudQuality;
    private View hudTrack, hudPlayed, hudRest;
    private ImageButton hudPause;
    private Button standbyButton, detailsButton, bootButton, exitButton, retryButton;
    private Button hudQualityButton, hudDetailsButton, hudEndButton;

    private Dialog modalDialog;
    private int qualityDialogSession = -1;
    private boolean hudRequested, errorFocused;
    private int displayedSession = -1;

    private final Runnable hideHud = () -> {
        if (modalDialog != null) return;
        hudRequested = false;
        if (service != null && service.player().isPlaying() && service.ui().error().isEmpty() && service.player().getPlayerError() == null) {
            hud.setVisibility(View.GONE);
        }
    };
    private final Runnable hideConnecting = () -> connecting.setVisibility(View.GONE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReceiverService.LocalBinder) binder).get();
            playerView.setPlayer(service.player());
            service.observe(MainActivity.this::render);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            waitingStatus.setText(R.string.cast_service_stopped);
            waitingError.setVisibility(View.VISIBLE);
            waitingError.setText(R.string.cast_service_stopped);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.castPlayer);
        playerView.setFocusable(false);

        waiting = findViewById(R.id.castWaiting);
        waitingStatus = findViewById(R.id.castWaitingStatus);
        address = findViewById(R.id.castAddress);
        received = findViewById(R.id.castReceived);
        waitingError = findViewById(R.id.castWaitingError);

        errorPanel = findViewById(R.id.castError);
        errorSubtitle = findViewById(R.id.castErrorSubtitle);
        retryButton = findViewById(R.id.castRetry);

        connecting = findViewById(R.id.castConnecting);
        connectingQuality = findViewById(R.id.castConnectingQuality);

        hud = findViewById(R.id.castHud);
        hudTitle = findViewById(R.id.castHudTitle);
        hudMeta = findViewById(R.id.castHudMeta);
        hudPosition = findViewById(R.id.castHudPosition);
        hudQuality = findViewById(R.id.castHudQuality);
        hudTrack = findViewById(R.id.castHudTrack);
        hudPlayed = findViewById(R.id.castHudPlayed);
        hudRest = findViewById(R.id.castHudRest);
        hudPause = findViewById(R.id.castHudPause);

        standbyButton = findViewById(R.id.castStandby);
        detailsButton = findViewById(R.id.castDetails);
        bootButton = findViewById(R.id.castBoot);
        exitButton = findViewById(R.id.castExit);
        hudQualityButton = findViewById(R.id.castHudQualityButton);
        hudDetailsButton = findViewById(R.id.castHudDetails);
        hudEndButton = findViewById(R.id.castHudEnd);

        bootButton.setText(bootLabel());
        standbyButton.setOnClickListener(v -> standby());
        detailsButton.setOnClickListener(v -> details());
        bootButton.setOnClickListener(v -> toggleBootStandby(bootButton));
        exitButton.setOnClickListener(v -> confirmExit());
        retryButton.setOnClickListener(v -> { if (service != null) service.reconnect(); });
        contentClick(R.id.castErrorDetails, v -> details());
        hudPause.setOnClickListener(v -> { if (service != null) service.togglePause(); showHud(); });
        hudQualityButton.setOnClickListener(v -> quality());
        hudDetailsButton.setOnClickListener(v -> details());
        hudEndButton.setOnClickListener(v -> { if (service != null) service.stopPlayback(); });

        bindFocus(standbyButton, detailsButton, bootButton, exitButton, retryButton,
                hudQualityButton, hudDetailsButton, hudEndButton, hudPause);
        standbyButton.requestFocus();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, ReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            bound = bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (RuntimeException failure) {
            waitingStatus.setText(R.string.cast_start_failed);
            waitingError.setVisibility(View.VISIBLE);
            waitingError.setText(failure.getClass().getSimpleName());
        }
    }

    /** Single render path: the service state is the only source of truth. */
    private void render() {
        if (service == null) return;
        if (service.isShuttingDown()) { finish(); return; }
        ReceiverService.Ui ui = service.ui();

        if (modalDialog != null && qualityDialogSession >= 0 && (!ui.video() || qualityDialogSession != ui.mediaGeneration())) modalDialog.dismiss();

        boolean failed = ui.video() && !ui.error().isEmpty();

        // ---- standby page ----
        waiting.setVisibility(ui.video() ? View.GONE : View.VISIBLE);
        waitingStatus.setText(ui.status());
        boolean hasAddress = !ui.address().isEmpty();
        address.setText(hasAddress ? ui.address() : "—");
        received.setText(getString(R.string.cast_received_value, ui.received()));
        String standbyProblem = ui.error();
        if (standbyProblem.isEmpty() && !hasAddress) standbyProblem = getString(R.string.cast_network_missing);
        waitingError.setVisibility(standbyProblem.isEmpty() ? View.GONE : View.VISIBLE);
        if (!standbyProblem.isEmpty()) waitingError.setText(standbyProblem);

        // ---- error page ----
        errorPanel.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed) {
            errorSubtitle.setText(ui.error());
            if (!errorFocused) { errorFocused = true; retryButton.requestFocus(); }
        } else {
            errorFocused = false;
        }

        boolean changed = video != ui.video();
        video = ui.video();
        boolean newSession = changed || displayedSession != ui.mediaGeneration();
        if (newSession) {
            hudRequested = false;
            handler.removeCallbacks(hideHud);
            handler.removeCallbacks(hideConnecting);
        }
        displayedSession = ui.mediaGeneration();

        if (ui.video() && !failed) {
            hudTitle.setText(ui.title());
            hudMeta.setText(ui.status() + "  ·  " + ui.qualityMode());
            hudQuality.setText(ui.quality());
            renderProgress(ui);

            boolean paused = service.player().getPlaybackState() == Player.STATE_READY && !service.player().getPlayWhenReady();
            hudPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
            hudPause.setContentDescription(getString(paused ? R.string.cast_btn_resume : R.string.cast_btn_pause));
            boolean attention = paused || service.player().getPlayerError() != null || service.player().getPlaybackState() == Player.STATE_ENDED;
            if (attention || hudRequested) {
                hud.setVisibility(View.VISIBLE);
            } else {
                hud.setVisibility(View.GONE);
            }
            if (newSession) {
                connectingQuality.setText(ui.quality());
                connecting.setVisibility(View.VISIBLE);
                hud.setVisibility(View.GONE);
                handler.postDelayed(hideConnecting, CONNECTING_HINT_MS);
            }
        } else {
            hud.setVisibility(View.GONE);
            connecting.setVisibility(View.GONE);
            if (changed && !ui.video()) standbyButton.requestFocus();
        }
    }

    /** A DLNA sink cannot seek, so the bar is a read-only indicator and only appears when duration is known. */
    private void renderProgress(ReceiverService.Ui ui) {
        long duration = service.player().getDuration();
        long position = Math.max(0, service.player().getCurrentPosition());
        boolean known = duration > 0 && duration != C.TIME_UNSET && position <= duration;
        hudTrack.setVisibility(known ? View.VISIBLE : View.GONE);
        if (known) {
            hudPosition.setText(Dlna.time(position));
            int percent = (int) Math.round(100d * position / duration);
            setWeight(hudPlayed, percent);
            setWeight(hudRest, Math.max(0, 100 - percent));
        } else {
            hudPosition.setText(ui.elapsed());
        }
    }

    private void setWeight(View view, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.weight = weight;
        view.setLayoutParams(params);
    }

    private void showHud() {
        if (!video) return;
        hudRequested = true;
        hud.setVisibility(View.VISIBLE);
        connecting.setVisibility(View.GONE);
        handler.removeCallbacks(hideConnecting);
        handler.removeCallbacks(hideHud);
        if (modalDialog == null) handler.postDelayed(hideHud, HUD_TIMEOUT_MS);
    }

    private void quality() {
        if (service == null || modalDialog != null) return;
        handler.removeCallbacks(hideHud);
        ReceiverService.QualityMenu menu = service.qualityMenu();

        View content = getLayoutInflater().inflate(R.layout.dialog_quality, null);
        TextView subtitle = (TextView) content.findViewById(R.id.castQualitySubtitle);
        TextView note = (TextView) content.findViewById(R.id.castQualityNote);
        LinearLayout rows = (LinearLayout) content.findViewById(R.id.castQualityRows);
        subtitle.setText(getString(R.string.cast_quality_subtitle, service.ui().quality()));

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);

        int focusIndex = 0;
        boolean autoChecked = menu.selectedKey().isEmpty();
        addQualityRow(rows, getString(R.string.cast_quality_auto), getString(R.string.cast_quality_auto_hint, service.ui().quality()), autoChecked, "", menu, dialog);
        for (int i = 0; i < menu.choices().size(); i++) {
            QualityCatalog.Choice choice = menu.choices().get(i);
            boolean checked = choice.key().equals(menu.selectedKey());
            if (checked) focusIndex = i + 1;
            addQualityRow(rows, choice.label(), "", checked, choice.key(), menu, dialog);
        }

        if (menu.loading()) note.setText(R.string.cast_quality_note_loading);
        else if (menu.choices().isEmpty()) note.setText(R.string.cast_quality_note_none);
        else if (menu.choices().size() < 2) note.setText(R.string.cast_quality_note_single);
        else note.setText(R.string.cast_quality_note_multi);

        modalDialog = dialog;
        qualityDialogSession = menu.generation();
        dialog.setOnDismissListener(d -> {
            if (modalDialog != dialog) return;
            modalDialog = null;
            qualityDialogSession = -1;
            if (service != null && video && service.ui().mediaGeneration() == menu.generation()) {
                showHud();
                hudQualityButton.requestFocus();
            } else {
                render();
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
            window.setLayout(dp(372), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        if (rows.getChildCount() > focusIndex) rows.getChildAt(focusIndex).requestFocus();
        else if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void addQualityRow(LinearLayout container, String label, String hint, boolean checked, String key,
                               ReceiverService.QualityMenu menu, Dialog dialog) {
        View row = getLayoutInflater().inflate(R.layout.item_quality_row, container, false);
        TextView labelView = (TextView) row.findViewById(R.id.castRowLabel);
        TextView hintView = (TextView) row.findViewById(R.id.castRowHint);
        ImageView check = (ImageView) row.findViewById(R.id.castRowCheck);
        labelView.setText(label);
        if (hint == null || hint.isEmpty()) hintView.setVisibility(View.GONE); else hintView.setText(hint);
        check.setVisibility(checked ? View.VISIBLE : View.GONE);
        bindFocus(row);
        row.setOnClickListener(v -> {
            boolean applied = service != null && service.selectQuality(menu.generation(), key);
            if (!applied) Toast.makeText(this, R.string.cast_quality_stale, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        container.addView(row);
    }

    private void details() {
        handler.removeCallbacks(hideHud);
        View content = getLayoutInflater().inflate(R.layout.dialog_details, null);
        LinearLayout facts = (LinearLayout) content.findViewById(R.id.castDetailFacts);
        LinearLayout checks = (LinearLayout) content.findViewById(R.id.castDetailChecks);
        TextView logView = (TextView) content.findViewById(R.id.castDetailLog);
        facts.removeAllViews();
        checks.removeAllViews();

        if (service == null) {
            addFact(facts, getString(R.string.cast_status_starting), getString(R.string.cast_details_pending));
        } else {
            ReceiverService.Ui ui = service.ui();
            addFact(facts, getString(R.string.cast_details_version), versionName());
            addFact(facts, getString(R.string.cast_details_tv), Build.MANUFACTURER + " " + Build.MODEL);
            addFact(facts, getString(R.string.cast_details_system), "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
            addFact(facts, getString(R.string.cast_details_receiver), ui.endpoint().isEmpty() ? "尚未就绪" : ui.endpoint());
            addFact(facts, getString(R.string.cast_details_quality), ui.quality() + " · " + ui.qualityMode());
            addFact(facts, getString(R.string.cast_details_received), getString(R.string.cast_received_value, ui.received()));
            addFact(facts, getString(R.string.cast_details_session), ui.elapsed());
            String logs = service.logs();
            if (logs.isEmpty()) logView.setVisibility(View.GONE); else logView.setText(logs);
        }
        addCheck(checks, "01", getString(R.string.cast_details_check_1));
        addCheck(checks, "02", getString(R.string.cast_details_check_2));
        addCheck(checks, "03", getString(R.string.cast_details_check_3));

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setOnDismissListener(d -> { if (modalDialog == dialog) modalDialog = null; showHud(); });
        modalDialog = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
            window.setLayout(dp(780), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addFact(LinearLayout parent, String label, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_detail_row, parent, false);
        ((TextView) row.findViewById(R.id.castFactLabel)).setText(label);
        ((TextView) row.findViewById(R.id.castFactValue)).setText(value);
        parent.addView(row);
    }

    private void addCheck(LinearLayout parent, String index, String text) {
        View row = getLayoutInflater().inflate(R.layout.item_check_row, parent, false);
        ((TextView) row.findViewById(R.id.castCheckIndex)).setText(index);
        ((TextView) row.findViewById(R.id.castCheckText)).setText(text);
        parent.addView(row);
    }

    private void standby() {
        final boolean allowed = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this);
        View content = getLayoutInflater().inflate(R.layout.dialog_standby, null);
        TextView body = (TextView) content.findViewById(R.id.castStandbyBody);
        View pathCard = content.findViewById(R.id.castStandbyPathCard);
        Button wait = (Button) content.findViewById(R.id.castStandbyWait);
        Button grant = (Button) content.findViewById(R.id.castStandbyGrant);

        body.setText(allowed ? R.string.cast_standby_body_allowed : R.string.cast_standby_body_denied);
        pathCard.setVisibility(allowed ? View.GONE : View.VISIBLE);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);

        Button cancel = (Button) content.findViewById(R.id.castStandbyCancel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        if (allowed) {
            wait.setVisibility(View.GONE);
            grant.setText(R.string.cast_btn_enter_background);
            grant.setOnClickListener(v -> { dialog.dismiss(); moveTaskToBack(true); });
        } else {
            wait.setOnClickListener(v -> { dialog.dismiss(); moveTaskToBack(true); });
            grant.setOnClickListener(v -> { dialog.dismiss(); openOverlaySettings(); });
        }
        bindFocus(wait, grant, cancel);
        dialog.setOnDismissListener(d -> { if (modalDialog == dialog) modalDialog = null; });
        modalDialog = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
            window.setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        grant.requestFocus();
    }

    private void exitReceiver() {
        if (service != null) service.shutdown();
        else stopService(new Intent(this, ReceiverService.class));
        finish();
    }

    private void confirmExit() {
        new AlertDialog.Builder(this, R.style.CastDialog)
                .setTitle(R.string.cast_exit_title)
                .setMessage(R.string.cast_exit_body)
                .setPositiveButton(R.string.cast_btn_exit_confirm, (dialog, which) -> exitReceiver())
                .setNegativeButton(R.string.cast_btn_keep, null)
                .show();
    }

    /** Xiaomi PatchWall often lacks the AOSP overlay screen; try the MIUI permission editor, then app details. */
    private void openOverlaySettings() {
        Uri pkg = Uri.parse("package:" + getPackageName());
        Intent[] candidates = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
                new Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", getPackageName()),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)};
        for (Intent intent : candidates) {
            try { startActivity(intent); return; } catch (RuntimeException unavailable) { }
        }
        Toast.makeText(this, R.string.cast_overlay_missing, Toast.LENGTH_LONG).show();
    }

    private String bootLabel() {
        return getString(BootReceiver.enabled(this) ? R.string.cast_btn_boot_on : R.string.cast_btn_boot_off);
    }

    private void toggleBootStandby(Button button) {
        boolean next = !BootReceiver.enabled(this);
        BootReceiver.setEnabled(this, next);
        button.setText(bootLabel());
        Toast.makeText(this, next ? R.string.cast_boot_on_toast : R.string.cast_boot_off_toast, Toast.LENGTH_LONG).show();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        // Xiaomi remotes have a Menu key but no dedicated quality button.
        if (code == KeyEvent.KEYCODE_MENU) {
            if (video) { showHud(); quality(); } else { details(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (service != null && service.ui().video()) service.stopPlayback(); else standby();
            return true;
        }
        if (video && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY)) {
            if (service != null) service.togglePause();
            showHud();
            return true;
        }
        if (video && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            boolean hidden = hud.getVisibility() != View.VISIBLE;
            showHud();
            if (hidden) { hudPause.requestFocus(); return true; }
        }
        return super.onKeyDown(code, event);
    }

    @Override protected void onStop() {
        if (modalDialog != null) modalDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        playerView.setPlayer(null);
        if (service != null) service.observe(null);
        if (bound) { unbindService(connection); bound = false; }
        service = null;
        super.onStop();
    }

    /** tvOS focus: a short scale on top of the white-block selector, never a translation. */
    private void bindFocus(View... views) {
        for (View view : views) {
            view.setOnFocusChangeListener((v, hasFocus) -> v.animate()
                    .scaleX(hasFocus ? 1.04f : 1f)
                    .scaleY(hasFocus ? 1.04f : 1f)
                    .setDuration(hasFocus ? 140L : 110L)
                    .start());
        }
    }

    private void contentClick(int id, View.OnClickListener listener) {
        View view = findViewById(id);
        view.setOnClickListener(listener);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
