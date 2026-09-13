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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_BT = 1001;
    private BluetoothAdapter adapter;
    private final List<BluetoothDevice> bonded = new ArrayList<>();
    private TextView status;
    private TextView deviceName;
    private Spinner spinner;
    private Button connect;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatTask;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (RemoteService.ACTION_STATE.equals(intent.getAction())) {
                String s = intent.getStringExtra(RemoteService.EXTRA_STATE);
                String n = intent.getStringExtra(RemoteService.EXTRA_DEVICE_NAME);
                boolean isConnected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false);
                setStatus(s == null ? "Ready" : s, isConnected);
                setDeviceName(n);
                connect.setText(isConnected ? "Disconnect" : "Connect");
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
    }

    private void ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_BT);
            return;
        }
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
        if (RemoteService.isConnected()) {
            startRemoteService(RemoteService.ACTION_DISCONNECT, null);
            return;
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this, "Pair your DAP first", Toast.LENGTH_LONG).show();
            return;
        }
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
        scroll.setBackgroundColor(Color.rgb(9, 11, 14));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.ic_launcher_foreground);
        hero.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        hero.setPadding(dp(12), dp(12), dp(12), dp(12));
        hero.setBackground(bg(Color.rgb(29, 33, 39), 30, Color.rgb(56, 62, 70)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(148), dp(148));
        hp.setMargins(0, dp(4), 0, dp(12));
        root.addView(hero, hp);

        TextView title = txt("DAP Remote", 31, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        root.addView(title, match());

        status = txt("Preparing…", 15, Color.rgb(214, 184, 119));
        status.setGravity(Gravity.CENTER);
        status.setTypeface(null, 1);
        status.setPadding(0, dp(4), 0, dp(10));
        root.addView(status, match());

        TextView deviceLabel = txt("CONNECTED DEVICE", 10, Color.rgb(112, 121, 133));
        deviceLabel.setGravity(Gravity.CENTER);
        deviceLabel.setTypeface(null, 1);
        root.addView(deviceLabel, match());

        deviceName = txt("—", 15, Color.rgb(214, 219, 226));
        deviceName.setGravity(Gravity.CENTER);
        deviceName.setTypeface(null, 1);
        deviceName.setPadding(0, dp(3), 0, dp(18));
        root.addView(deviceName, match());

        LinearLayout playbackCard = card();
        playbackCard.addView(section("PLAYBACK"), match());
        LinearLayout transport = row();
        Button prev = control("⏮", 27), play = control("▶  ❚❚", 18), next = control("⏭", 27);
        prev.setOnClickListener(v -> command(RemoteService.ACTION_PREV));
        play.setOnClickListener(v -> command(RemoteService.ACTION_PLAY_PAUSE));
        next.setOnClickListener(v -> command(RemoteService.ACTION_NEXT));
        addEqual(transport, prev, 66); addEqual(transport, play, 66); addEqual(transport, next, 66);
        playbackCard.addView(transport, rowParams(0));
        root.addView(playbackCard, cardParams());

        LinearLayout volumeCard = card();
        volumeCard.addView(section("VOLUME"), match());
        LinearLayout vol = row();
        Button down = control("−", 31), mute = control("Mute", 15), up = control("+", 31);
        mute.setOnClickListener(v -> command(RemoteService.ACTION_MUTE));
        enableRepeat(down, RemoteService.ACTION_VOL_DOWN);
        enableRepeat(up, RemoteService.ACTION_VOL_UP);
        addEqual(vol, down, 62); addEqual(vol, mute, 62); addEqual(vol, up, 62);
        volumeCard.addView(vol, rowParams(0));
        TextView volumeHint = txt("Tap − / + for one step · hold for continuous volume", 11, Color.rgb(112, 121, 133));
        volumeHint.setGravity(Gravity.CENTER);
        volumeHint.setPadding(dp(6), dp(9), dp(6), 0);
        volumeCard.addView(volumeHint, match());
        root.addView(volumeCard, cardParams());

        LinearLayout systemCard = card();
        systemCard.addView(section("SYSTEM"), match());
        LinearLayout system = row();
        Button screen = control("⏻\nScreen On / Off", 14), back = control("↩\nBack", 16);
        screen.setOnClickListener(v -> command(RemoteService.ACTION_SCREEN_TOGGLE));
        back.setOnClickListener(v -> command(RemoteService.ACTION_BACK));
        addEqual(system, screen, 76); addEqual(system, back, 76);
        systemCard.addView(system, rowParams(0));
        root.addView(systemCard, cardParams());

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
        addEqual(pair, p, 50); addEqual(pair, r, 50);
        connectionCard.addView(pair, rowParams(9));

        connect = wide("Connect");
        connect.setEnabled(false);
        connect.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams cp = match();
        cp.height = dp(54);
        connectionCard.addView(connect, cp);

        TextView auto = txt("Auto Connect is always active for the last paired DAP while the background service is running.", 11, Color.rgb(112, 121, 133));
        auto.setGravity(Gravity.CENTER);
        auto.setPadding(dp(8), dp(10), dp(8), 0);
        connectionCard.addView(auto, match());
        root.addView(connectionCard, cardParams());

        setContentView(scroll);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(11), dp(12), dp(12));
        l.setBackground(bg(Color.rgb(22, 25, 30), 22, Color.rgb(48, 54, 62)));
        return l;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = match();
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }

    private void enableRepeat(Button b, String action) {
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                command(action);
                repeatTask = new Runnable() {
                    @Override public void run() {
                        command(action);
                        repeatHandler.postDelayed(this, 170);
                    }
                };
                repeatHandler.postDelayed(repeatTask, 450);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) stopRepeat();
            return true;
        });
    }

    private void stopRepeat() {
        if (repeatTask != null) {
            repeatHandler.removeCallbacks(repeatTask);
            repeatTask = null;
        }
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    private void addEqual(LinearLayout row, Button b, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        row.addView(b, p);
    }

    private LinearLayout.LayoutParams rowParams(int bottom) {
        LinearLayout.LayoutParams p = match();
        p.setMargins(-dp(4), 0, -dp(4), dp(bottom));
        return p;
    }

    private TextView section(String s) {
        TextView v = txt(s, 10, Color.rgb(118, 126, 138));
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

    private Button baseButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackground(bg(Color.rgb(34, 38, 45), 20, Color.rgb(57, 64, 74)));
        return b;
    }

    private Button control(String s, int z) { Button b = baseButton(s); b.setTextSize(z); return b; }
    private Button small(String s) { Button b = baseButton(s); b.setTextSize(14); return b; }
    private Button wide(String s) { Button b = baseButton(s); b.setTextSize(16); return b; }
    private TextView txt(String s, int z, int c) { TextView v = new TextView(this); v.setText(s); v.setTextSize(z); v.setTextColor(c); return v; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }

    private void setStatus(String s, boolean isConnected) {
        status.setText((isConnected ? "●  " : "○  ") + s);
        status.setTextColor(isConnected ? Color.rgb(104, 224, 137) : Color.rgb(201, 176, 119));
    }

    private void setDeviceName(String n) {
        if (n == null || n.trim().isEmpty() || "DAP".equals(n)) deviceName.setText("—");
        else deviceName.setText(n.trim());
    }
}
