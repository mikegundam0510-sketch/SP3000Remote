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
import android.widget.SeekBar;
import android.widget.Spinner;
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
    private SeekBar volumeSlider;

    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatTask;
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private int approxVolume;
    private int queuedVolumeSteps = 0;
    private int queuedVolumeDirection = 0;

    private final Runnable volumeStepTask = new Runnable() {
        @Override public void run() {
            if (queuedVolumeSteps <= 0 || queuedVolumeDirection == 0) return;
            command(queuedVolumeDirection > 0 ? RemoteService.ACTION_VOL_UP : RemoteService.ACTION_VOL_DOWN);
            setApproxVolume(approxVolume + queuedVolumeDirection, true);
            queuedVolumeSteps--;
            if (queuedVolumeSteps > 0) volumeHandler.postDelayed(this, 45);
            else queuedVolumeDirection = 0;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!RemoteService.ACTION_STATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra(RemoteService.EXTRA_STATE);
            String n = intent.getStringExtra(RemoteService.EXTRA_DEVICE_NAME);
            boolean isConnected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false);
            setStatus(s == null ? "Ready" : s, isConnected);
            setDeviceName(n);
            connect.setText(isConnected ? "Disconnect" : "Connect");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        approxVolume = clampVolume(getSharedPreferences("remote", MODE_PRIVATE).getInt("approx_volume", DEFAULT_APPROX_VOLUME));
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("Bluetooth unavailable", false);
            connect.setEnabled(false);
            return;
        }
        ensurePermissions();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(RemoteService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, f);
    }

    @Override protected void onStop() {
        super.onStop();
        stopRepeat();
        volumeHandler.removeCallbacks(volumeStepTask);
        queuedVolumeSteps = 0;
        queuedVolumeDirection = 0;
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
        if (!allowed()) return;
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

    private boolean allowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String name(BluetoothDevice d) {
        if (!allowed()) return "Bluetooth device";
        String n = d.getName();
        return n == null || n.trim().isEmpty() ? "Bluetooth device" : n;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4, 6, 8));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.ic_dap_hero);
        hero.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(145), dp(205));
        hp.setMargins(0, dp(2), 0, dp(2));
        root.addView(hero, hp);

        TextView title = txt("DAP Remote", 31, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        root.addView(title, match());

        status = txt("Preparing…", 16, Color.rgb(188, 194, 203));
        status.setGravity(Gravity.CENTER);
        status.setTypeface(null, 1);
        status.setPadding(0, dp(5), 0, dp(10));
        root.addView(status, match());

        TextView deviceLabel = txt("CONNECTED DEVICE", 10, Color.rgb(132, 141, 153));
        deviceLabel.setGravity(Gravity.CENTER);
        deviceLabel.setTypeface(null, 1);
        root.addView(deviceLabel, match());

        deviceName = txt("—", 18, Color.rgb(230, 233, 238));
        deviceName.setGravity(Gravity.CENTER);
        deviceName.setTypeface(null, 1);
        deviceName.setPadding(0, dp(3), 0, dp(15));
        root.addView(deviceName, match());

        LinearLayout playbackCard = card();
        LinearLayout transport = row();
        ImageButton prev = roundIconButton(R.drawable.ic_prev);
        ImageButton play = roundIconButton(R.drawable.ic_play_pause);
        ImageButton next = roundIconButton(R.drawable.ic_next);
        prev.setOnClickListener(v -> command(RemoteService.ACTION_PREV));
        play.setOnClickListener(v -> command(RemoteService.ACTION_PLAY_PAUSE));
        next.setOnClickListener(v -> command(RemoteService.ACTION_NEXT));
        addCircle(transport, prev, 78);
        addCircle(transport, play, 78);
        addCircle(transport, next, 78);
        playbackCard.addView(transport, match());
        root.addView(playbackCard, cardParams());

        LinearLayout volumeCard = card();
        LinearLayout volumeHeader = row();
        TextView volumeTitle = txt("Volume (Approx.)", 13, Color.rgb(234, 236, 240));
        volumeTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        volumeValue = txt(approxVolume + " / 150", 13, Color.rgb(234, 236, 240));
        volumeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeHeader.addView(volumeTitle, new LinearLayout.LayoutParams(0, dp(28), 1f));
        volumeHeader.addView(volumeValue, new LinearLayout.LayoutParams(0, dp(28), 1f));
        volumeCard.addView(volumeHeader, match());

        LinearLayout vol = row();
        ImageButton down = roundIconButton(R.drawable.ic_minus);
        ImageButton up = roundIconButton(R.drawable.ic_plus);
        enableVolumeRepeat(down, -1);
        enableVolumeRepeat(up, 1);

        volumeSlider = new SeekBar(this);
        volumeSlider.setMax(VOLUME_MAX - VOLUME_MIN);
        volumeSlider.setProgress(approxVolume - VOLUME_MIN);
        volumeSlider.setProgressTintList(ColorStateList.valueOf(Color.rgb(35, 132, 255)));
        volumeSlider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(54, 60, 69)));
        volumeSlider.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int previewTarget = approxVolume;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { previewTarget = progress + VOLUME_MIN; volumeValue.setText(previewTarget + " / 150"); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                volumeHandler.removeCallbacks(volumeStepTask);
                queuedVolumeSteps = 0;
                queuedVolumeDirection = 0;
                previewTarget = seekBar.getProgress() + VOLUME_MIN;
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { queueVolumeTarget(clampVolume(previewTarget)); }
        });

        vol.addView(down, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        slp.setMargins(dp(8), 0, dp(8), 0);
        vol.addView(volumeSlider, slp);
        vol.addView(up, new LinearLayout.LayoutParams(dp(58), dp(58)));
        volumeCard.addView(vol, match());

        TextView volumeHint = txt("Approximate 0–150 position based on commands sent by this app.", 10, Color.rgb(115, 124, 137));
        volumeHint.setGravity(Gravity.CENTER);
        volumeHint.setPadding(dp(4), dp(6), dp(4), 0);
        volumeCard.addView(volumeHint, match());
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

        LinearLayout connectionCard = card();
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

    private void queueVolumeTarget(int target) {
        target = clampVolume(target);
        volumeHandler.removeCallbacks(volumeStepTask);
        int delta = target - approxVolume;
        if (delta == 0) { setApproxVolume(target, false); return; }
        queuedVolumeDirection = delta > 0 ? 1 : -1;
        queuedVolumeSteps = Math.abs(delta);
        volumeHandler.post(volumeStepTask);
    }

    private void changeApproxVolumeOneStep(int direction) {
        int next = clampVolume(approxVolume + direction);
        if (next == approxVolume) return;
        command(direction > 0 ? RemoteService.ACTION_VOL_UP : RemoteService.ACTION_VOL_DOWN);
        setApproxVolume(next, true);
    }

    private void setApproxVolume(int value, boolean persist) {
        approxVolume = clampVolume(value);
        if (volumeSlider != null) volumeSlider.setProgress(approxVolume - VOLUME_MIN);
        if (volumeValue != null) volumeValue.setText(approxVolume + " / 150");
        if (persist) getSharedPreferences("remote", MODE_PRIVATE).edit().putInt("approx_volume", approxVolume).apply();
    }

    private int clampVolume(int value) { return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, value)); }

    private void enableVolumeRepeat(View v, int direction) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                changeApproxVolumeOneStep(direction);
                repeatTask = new Runnable() {
                    @Override public void run() {
                        changeApproxVolumeOneStep(direction);
                        repeatHandler.postDelayed(this, 170);
                    }
                };
                repeatHandler.postDelayed(repeatTask, 450);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) stopRepeat();
            return true;
        });
    }

    private void stopRepeat() {
        if (repeatTask != null) { repeatHandler.removeCallbacks(repeatTask); repeatTask = null; }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(13), dp(14), dp(13));
        l.setBackground(bg(Color.rgb(18, 21, 26), 21, Color.rgb(52, 59, 68)));
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
        b.setBackground(ovalBg(Color.rgb(31, 35, 42), Color.rgb(63, 70, 80)));
        return b;
    }

    private View systemTile(int iconRes, String label) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(12), dp(18), dp(12), dp(16));
        tile.setBackground(bg(Color.rgb(25, 29, 35), 22, Color.rgb(56, 64, 73)));
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
        p.setMargins(dp(10), 0, dp(10), 0);
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
        v.setTypeface(null, 1);
        v.setPadding(dp(4), 0, 0, dp(8));
        return v;
    }

    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable ovalBg(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
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
        if (n == null || n.trim().isEmpty() || "DAP".equals(n)) deviceName.setText("—");
        else deviceName.setText(n.trim());
    }
}
