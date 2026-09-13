package com.example.sp3000remote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_BT = 1001;
    private static final int VOLUME_MIN = 0;
    private static final int VOLUME_MAX = 150;
    private static final int DEFAULT_APPROX_VOLUME = 75;
    private static final int ICON_DP = 32;

    private BluetoothAdapter adapter;
    private final List<BluetoothDevice> bonded = new ArrayList<>();
    private TextView status;
    private TextView deviceName;
    private TextView volumeValue;
    private Spinner spinner;
    private Button connect;
    private SeekBar volumeIndicator;
    private ImageButton playButton;
    private int approxVolume;
    private LinearLayout connectionCard;
    private final Handler volumeRepeatHandler = new Handler(Looper.getMainLooper());
    private Runnable volumeRepeatTask;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!RemoteService.ACTION_STATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra(RemoteService.EXTRA_STATE);
            String n = intent.getStringExtra(RemoteService.EXTRA_DEVICE_NAME);
            boolean isConnected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false);
            setStatus(s == null ? "Ready" : s, isConnected);
            setDeviceName(n);
            connect.setText(isConnected ? "Disconnect" : "Connect");
            if (intent.hasExtra(RemoteService.EXTRA_PLAYING)) {
                setPlayButtonIcon(intent.getBooleanExtra(RemoteService.EXTRA_PLAYING, false));
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        approxVolume = clampVolume(getSharedPreferences("remote", MODE_PRIVATE)
            .getInt("approx_volume", DEFAULT_APPROX_VOLUME));
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("Bluetooth unavailable", false);
            connect.setEnabled(false);
            return;
        }
        ensurePermissions();
    }

    // Android 13+ uses the non-exported flag; older Android uses the legacy overload.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(RemoteService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, f);
        setPlayButtonIcon(getSharedPreferences("remote", MODE_PRIVATE).getBoolean("is_playing", false));
    }

    @Override protected void onStop() {
        super.onStop();
        stopVolumeRepeat();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
    }

    private void ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQ_BT); return; }
        ready();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == REQ_BT) ready();
    }

    private void ready() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Bluetooth permission required", false);
            connect.setEnabled(false);
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Turn Bluetooth on", false);
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        refresh();
        connect.setEnabled(true);
        startRemoteService(RemoteService.ACTION_START, null);
    }

    private void refresh() {
        if ((Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        bonded.clear();
        Set<BluetoothDevice> set = adapter.getBondedDevices();
        if (set != null) bonded.addAll(set);
        List<String> labels = new ArrayList<>();
        int preferred = 0;
        for (int i = 0; i < bonded.size(); i++) {
            BluetoothDevice d = bonded.get(i);
            String n = name(d);
            labels.add(n + "\n" + d.getAddress());
            String upper = n.toUpperCase();
            if (upper.contains("SP3000") || upper.contains("SP4000")) preferred = i;
        }
        if (labels.isEmpty()) labels.add("No paired DAP — pair first");
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (!bonded.isEmpty()) spinner.setSelection(preferred);
    }

    private void toggle() {
        if (RemoteService.isConnected()) { startRemoteService(RemoteService.ACTION_DISCONNECT, null); return; }
        if (bonded.isEmpty()) { Toast.makeText(this, "Pair your DAP first", Toast.LENGTH_LONG).show(); return; }
        int pos = Math.max(0, spinner.getSelectedItemPosition());
        BluetoothDevice d = bonded.get(Math.min(pos, bonded.size() - 1));
        setDeviceName(name(d));
        startRemoteService(RemoteService.ACTION_CONNECT, d.getAddress());
    }

    private void discover() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT);
            return;
        }
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);
        setStatus("Pair from your DAP Bluetooth settings", false);
    }

    private void command(String action) { startRemoteService(action, null); }

    private void startRemoteService(String action, String address) {
        Intent i = new Intent(this, RemoteService.class).setAction(action);
        if (address != null) i.putExtra(RemoteService.EXTRA_ADDRESS, address);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }



    private String name(BluetoothDevice d) {
        if ((Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return "Bluetooth device";
        String n = d.getName();
        return n == null || n.trim().isEmpty() ? "Bluetooth device" : n;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(27, 31, 34), Color.rgb(5, 6, 7), Color.BLACK});
        scroll.setBackground(backdrop);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
                root.setPadding(dp(18) + bars.left, dp(10) + bars.top,
                    dp(18) + bars.right, dp(28) + bars.bottom);
                return insets;
            });
        }

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.END);
        ImageButton settings = roundIconButton(R.drawable.ic_settings);
        settings.setPadding(dp(10), dp(10), dp(10), dp(10));
        settings.setBackgroundColor(Color.TRANSPARENT);
        settings.setContentDescription("Connection settings");
        settings.setOnClickListener(v -> {
            boolean show = connectionCard.getVisibility() != View.VISIBLE;
            connectionCard.setVisibility(show ? View.VISIBLE : View.GONE);
            settings.setContentDescription(show ? "Hide connection settings" : "Connection settings");
            if (show) scroll.post(() -> scroll.smoothScrollTo(0, connectionCard.getBottom()));
        });
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar, match());

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.ic_dap_hero);
        hero.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(155), dp(205));
        hp.setMargins(0, dp(2), 0, dp(2));
        root.addView(hero, hp);

        TextView title = txt("DAP Remote", 31, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, match());

        status = txt("Preparing…", 16, Color.rgb(188, 194, 203));
        status.setGravity(Gravity.CENTER);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(0, dp(5), 0, dp(10));
        root.addView(status, match());

        deviceName = txt("SP3000 / SP4000", 15, Color.rgb(188, 194, 203));
        deviceName.setGravity(Gravity.CENTER);
        deviceName.setTypeface(null, android.graphics.Typeface.BOLD);
        deviceName.setPadding(0, dp(3), 0, dp(15));
        root.addView(deviceName, match());

        LinearLayout playbackCard = card();
        LinearLayout transport = row();
        ImageButton prev = roundIconButton(R.drawable.ic_prev);
        playButton = roundIconButton(R.drawable.ic_play);
        ImageButton play = playButton;
        ImageButton next = roundIconButton(R.drawable.ic_next);
        prev.setOnClickListener(v -> command(RemoteService.ACTION_PREV));
        play.setOnClickListener(v -> command(RemoteService.ACTION_PLAY_PAUSE));
        next.setOnClickListener(v -> command(RemoteService.ACTION_NEXT));
        prev.setContentDescription("Previous track");
        play.setContentDescription("Play / Pause");
        next.setContentDescription("Next track");
        int side = Math.min(76, Math.max(48, (getResources().getConfiguration().screenWidthDp - 116) / 3));
        addCircle(transport, prev, side);
        addCircle(transport, play, side + 16);
        addCircle(transport, next, side);
        playbackCard.addView(transport, match());
        root.addView(playbackCard, cardParams());

        LinearLayout volumeCard = card();
        LinearLayout volumeHeader = row();
        TextView volumeTitle = txt("Volume", 13, Color.rgb(234, 236, 240));
        volumeValue = txt(approxVolume + " / 150", 15, Color.WHITE);
        volumeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeHeader.addView(volumeTitle, new LinearLayout.LayoutParams(0, dp(28), 1f));
        volumeHeader.addView(volumeValue, new LinearLayout.LayoutParams(0, dp(28), 1f));
        volumeCard.addView(volumeHeader, match());
        LinearLayout volumeControls = row();
        ImageButton down = roundIconButton(R.drawable.ic_minus);
        ImageButton up = roundIconButton(R.drawable.ic_plus);
        down.setContentDescription("Volume down one step");
        up.setContentDescription("Volume up one step");
        enableVolumeRepeat(down, -1);
        enableVolumeRepeat(up, 1);

        // Display only: the bar follows +/- commands and cannot receive touch or key input.
        volumeIndicator = new SeekBar(this);
        volumeIndicator.setMax(VOLUME_MAX - VOLUME_MIN);
        volumeIndicator.setProgress(approxVolume - VOLUME_MIN);
        volumeIndicator.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        volumeIndicator.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(54, 57, 60)));
        volumeIndicator.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        volumeIndicator.setEnabled(false);
        volumeIndicator.setAlpha(1f);
        volumeIndicator.setFocusable(false);
        volumeIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        volumeControls.addView(down, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(58), 1f);
        barParams.setMargins(dp(8), 0, dp(8), 0);
        volumeControls.addView(volumeIndicator, barParams);
        volumeControls.addView(up, new LinearLayout.LayoutParams(dp(58), dp(58)));
        volumeCard.addView(volumeControls, match());
        root.addView(volumeCard, cardParams());

        LinearLayout system = row();
        View screen = systemTile(R.drawable.ic_power, "Screen\nOn / Off");
        View back = systemTile(R.drawable.ic_back, "Back");
        screen.setOnClickListener(v -> command(RemoteService.ACTION_SCREEN_TOGGLE));
        back.setOnClickListener(v -> command(RemoteService.ACTION_BACK));
        addTile(system, screen);
        addTile(system, back);
        LinearLayout.LayoutParams sysP = match();
        sysP.setMargins(-dp(4), 0, -dp(4), dp(12));
        root.addView(system, sysP);

        connectionCard = card();
        connectionCard.setVisibility(View.GONE);
        connectionCard.addView(section("CONNECTION"), match());
        spinner = new Spinner(this);
        LinearLayout.LayoutParams sp = match();
        sp.setMargins(0, 0, 0, dp(9));
        connectionCard.addView(spinner, sp);

        LinearLayout pair = row();
        Button p = small("Pair DAP"), r = small("Refresh");
        p.setOnClickListener(v -> discover());
        r.setOnClickListener(v -> refresh());
        addEqual(pair, p, 48); addEqual(pair, r, 48);
        connectionCard.addView(pair, rowParams(8));

        connect = wide("Connect");
        connect.setEnabled(false);
        connect.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams cp = match(); cp.height = dp(52);
        connectionCard.addView(connect, cp);

        TextView auto = txt("Auto Connect remains active for the last paired DAP while the background service is running.", 10, Color.rgb(112, 121, 133));
        auto.setGravity(Gravity.CENTER);
        auto.setPadding(dp(8), dp(9), dp(8), 0);
        connectionCard.addView(auto, match());
        root.addView(connectionCard, cardParams());

        setContentView(scroll);
    }

    private void changeVolumeOneStep(int direction) {
        int next = clampVolume(approxVolume + direction);
        if (next == approxVolume) return;
        command(direction > 0 ? RemoteService.ACTION_VOL_UP : RemoteService.ACTION_VOL_DOWN);
        approxVolume = next;
        volumeIndicator.setProgress(approxVolume - VOLUME_MIN);
        volumeValue.setText(approxVolume + " / 150");
        getSharedPreferences("remote", MODE_PRIVATE).edit()
            .putInt("approx_volume", approxVolume).apply();
    }

    private void enableVolumeRepeat(View button, int direction) {
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                changeVolumeOneStep(direction);
                stopVolumeRepeat();
                volumeRepeatTask = new Runnable() {
                    @Override public void run() {
                        changeVolumeOneStep(direction);
                        volumeRepeatHandler.postDelayed(this, 90L);
                    }
                };
                volumeRepeatHandler.postDelayed(volumeRepeatTask, 450L);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopVolumeRepeat();
                return true;
            }
            return true;
        });
    }

    private void stopVolumeRepeat() {
        if (volumeRepeatTask != null) {
            volumeRepeatHandler.removeCallbacks(volumeRepeatTask);
            volumeRepeatTask = null;
        }
    }

    private int clampVolume(int value) {
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, value));
    }

    private void setPlayButtonIcon(boolean playing) {
        if (playButton != null) {
            playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(13), dp(14), dp(13));
        l.setBackground(bg(Color.rgb(22, 24, 25), 21, Color.rgb(49, 52, 54)));
        return l;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = match(); p.setMargins(0, 0, 0, dp(12)); return p;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    private ImageButton roundIconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp((78 - ICON_DP) / 2);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(ovalBg(Color.rgb(34, 37, 39), Color.rgb(64, 67, 70)));
        return b;
    }

    private View systemTile(int iconRes, String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setContentDescription(label.replace("\n", " "));
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(12), dp(18), dp(12), dp(16));
        tile.setBackground(bg(Color.rgb(22, 24, 25), 22, Color.rgb(49, 52, 54)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)));
        TextView text = txt(label, 16, Color.WHITE);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = match(); tp.setMargins(0, dp(10), 0, 0);
        tile.addView(text, tp);
        return tile;
    }

    private void addCircle(LinearLayout row, View v, int size) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(size), dp(size), 0f);
        p.setMargins(dp(6), 0, dp(6), 0);
        int pad = dp((size - ICON_DP) / 2);
        v.setPadding(pad, pad, pad, pad);
        row.addView(v, p);
    }

    private void addTile(LinearLayout row, View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(128), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        row.addView(v, p);
    }

    private void addEqual(LinearLayout row, Button b, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        row.addView(b, p);
    }

    private LinearLayout.LayoutParams rowParams(int bottom) {
        LinearLayout.LayoutParams p = match(); p.setMargins(-dp(4), 0, -dp(4), dp(bottom)); return p;
    }

    private TextView section(String s) {
        TextView v = txt(s, 10, Color.rgb(121, 130, 142));
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), 0, 0, dp(8));
        return v;
    }

    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColors(new int[]{color, Color.rgb(13, 15, 16)});
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable ovalBg(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{color, Color.rgb(13, 15, 16)});
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Button baseButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackground(bg(Color.rgb(30, 34, 41), 20, Color.rgb(58, 65, 75)));
        return b;
    }

    private Button small(String s) { Button b = baseButton(s); b.setTextSize(14); return b; }
    private Button wide(String s) { Button b = baseButton(s); b.setTextSize(16); return b; }
    private TextView txt(String s, int z, int c) { TextView v = new TextView(this); v.setText(s); v.setTextSize(z); v.setTextColor(c); return v; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }

    private void setStatus(String s, boolean isConnected) {
        status.setText((isConnected ? "●  " : "○  ") + s);
        status.setTextColor(isConnected ? Color.rgb(61, 222, 118) : Color.rgb(188, 194, 203));
    }

    private void setDeviceName(String n) {
        if (n == null || n.trim().isEmpty() || "DAP".equals(n)) deviceName.setText("SP3000 / SP4000");
        else deviceName.setText(n.trim());
    }
}
