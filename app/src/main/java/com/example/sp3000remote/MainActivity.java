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
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private Spinner spinner;
    private Button connect;
    private LinearLayout controls;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (RemoteService.ACTION_STATE.equals(intent.getAction())) {
                String s = intent.getStringExtra(RemoteService.EXTRA_STATE);
                boolean connected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false);
                setStatus(s == null ? "Ready" : s);
                connect.setText(connected ? "Disconnect" : "Connect");
                controls.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("This phone has no Bluetooth adapter");
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
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ_BT) ready();
    }

    private void ready() {
        if (!adapter.isEnabled()) {
            setStatus("Turn Bluetooth on first");
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
        for (int i=0;i<bonded.size();i++) {
            BluetoothDevice d = bonded.get(i);
            String n = name(d);
            labels.add(n + "\n" + d.getAddress());
            if (n.toUpperCase().contains("SP3000")) preferred = i;
        }
        if (labels.isEmpty()) labels.add("No paired devices — pair SP3000 first");
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (!bonded.isEmpty()) spinner.setSelection(preferred);
    }

    private void toggle() {
        if (RemoteService.isConnected()) {
            startRemoteService(RemoteService.ACTION_DISCONNECT, null);
            return;
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this,"Pair SP3000 first",Toast.LENGTH_LONG).show();
            return;
        }
        int pos = Math.max(0, spinner.getSelectedItemPosition());
        BluetoothDevice d = bonded.get(Math.min(pos, bonded.size()-1));
        startRemoteService(RemoteService.ACTION_CONNECT, d.getAddress());
    }

    private void discover() {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},REQ_BT);
            return;
        }
        Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300);
        startActivity(i);
        setStatus("Phone discoverable — pair it from SP3000 Bluetooth settings");
    }

    private void command(String action) { startRemoteService(action, null); }

    private void startRemoteService(String action, String address) {
        Intent i = new Intent(this, RemoteService.class).setAction(action);
        if (address != null) i.putExtra(RemoteService.EXTRA_ADDRESS, address);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private boolean allowed() { return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED; }
    private String name(BluetoothDevice d) { if(!allowed()) return "Bluetooth device"; String n=d.getName(); return n==null||n.trim().isEmpty()?"Bluetooth device":n; }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(34),dp(20),dp(20)); root.setBackgroundColor(Color.rgb(11,13,16));
        TextView title=txt("SP3000 REMOTE",28,Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title,match());
        TextView sub=txt("Bluetooth HID remote + lock screen + widget",14,Color.rgb(160,164,172)); sub.setGravity(Gravity.CENTER); root.addView(sub,match());
        status=txt("Preparing…",14,Color.rgb(208,180,119)); status.setGravity(Gravity.CENTER); status.setPadding(0,dp(18),0,dp(12)); root.addView(status,match());
        spinner=new Spinner(this); root.addView(spinner,match());
        LinearLayout pair=new LinearLayout(this); Button p=btn("Pair new SP3000"), r=btn("Refresh"); p.setOnClickListener(v->discover()); r.setOnClickListener(v->refresh()); pair.addView(p,weight()); pair.addView(r,weight()); root.addView(pair,match());
        connect=btn("Connect"); connect.setEnabled(false); connect.setOnClickListener(v->toggle()); root.addView(connect,match());
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setVisibility(View.INVISIBLE);
        LinearLayout transport=new LinearLayout(this); Button prev=big("⏮",42), play=big("▶  ❚❚",26), next=big("⏭",42); prev.setOnClickListener(v->command(RemoteService.ACTION_PREV)); play.setOnClickListener(v->command(RemoteService.ACTION_PLAY_PAUSE)); next.setOnClickListener(v->command(RemoteService.ACTION_NEXT)); transport.addView(prev,weight()); transport.addView(play,weight()); transport.addView(next,weight()); controls.addView(transport,match());
        LinearLayout vol=new LinearLayout(this); Button down=big("−",38), mute=big("Mute",18), up=big("+",38); down.setOnClickListener(v->command(RemoteService.ACTION_VOL_DOWN)); mute.setOnClickListener(v->command(RemoteService.ACTION_MUTE)); up.setOnClickListener(v->command(RemoteService.ACTION_VOL_UP)); vol.addView(down,weight()); vol.addView(mute,weight()); vol.addView(up,weight()); controls.addView(vol,match()); root.addView(controls,match());
        TextView hint=txt("After connecting once, the background service remembers your SP3000. Add the SP3000 Remote widget to your Home Screen, or use the lock-screen notification controls.",13,Color.rgb(145,150,158)); hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(24),0,0); root.addView(hint,match());
        setContentView(root);
    }

    private TextView txt(String s,int z,int c){ TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);return v; }
    private Button btn(String s){ Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(38,42,48));return b; }
    private Button big(String s,int z){ Button b=btn(s);b.setTextSize(z);b.setMinHeight(dp(88));return b; }
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1f);}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private void setStatus(String s){status.setText(s);}
}
