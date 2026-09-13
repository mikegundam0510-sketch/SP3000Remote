package com.example.sp3000remote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ_BT = 1001, REPORT_ID = 1;
    private static final byte[] HID_DESCRIPTOR = new byte[]{
        0x05,0x0C,0x09,0x01,(byte)0xA1,0x01,(byte)0x85,REPORT_ID,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x07,
        0x09,(byte)0xCD,0x09,(byte)0xB5,0x09,(byte)0xB6,0x09,(byte)0xB7,
        0x09,(byte)0xE9,0x09,(byte)0xEA,0x09,(byte)0xE2,
        (byte)0x81,0x02,0x75,0x01,(byte)0x95,0x01,(byte)0x81,0x03,(byte)0xC0
    };

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice selected;
    private final List<BluetoothDevice> bonded = new ArrayList<>();
    private TextView status;
    private Spinner spinner;
    private Button connect;
    private LinearLayout controls;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor = Runnable::run;

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean registered) {
            runOnUiThread(() -> { setStatus(registered ? "HID ready — choose SP3000 and connect" : "HID registration stopped"); connect.setEnabled(registered); });
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            runOnUiThread(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    selected = device; setStatus("Connected to " + name(device)); connect.setText("Disconnect"); controls.setVisibility(View.VISIBLE);
                } else if (state == BluetoothProfile.STATE_CONNECTING) setStatus("Connecting to " + name(device) + "…");
                else if (state == BluetoothProfile.STATE_DISCONNECTED) { setStatus("Disconnected"); connect.setText("Connect"); controls.setVisibility(View.INVISIBLE); }
            });
        }
    };

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) { if (profile == BluetoothProfile.HID_DEVICE) { hid = (BluetoothHidDevice) proxy; registerHid(); } }
        @Override public void onServiceDisconnected(int profile) { if (profile == BluetoothProfile.HID_DEVICE) { hid = null; runOnUiThread(() -> setStatus("Bluetooth HID service unavailable")); } }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); buildUi(); adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { setStatus("This phone has no Bluetooth adapter"); connect.setEnabled(false); return; }
        ensurePermissions();
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQ_BT); return; }
        }
        startHid();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ_BT) { for (int x:g) if (x != PackageManager.PERMISSION_GRANTED) { setStatus("Bluetooth permission is required"); return; } startHid(); }
    }

    private void startHid() {
        if (!adapter.isEnabled()) { setStatus("Turn Bluetooth on first"); startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        refresh(); setStatus("Starting Bluetooth HID…"); adapter.getProfileProxy(this, listener, BluetoothProfile.HID_DEVICE);
    }

    private void registerHid() {
        if (hid == null || !allowed()) return;
        BluetoothHidDeviceAppSdpSettings s = new BluetoothHidDeviceAppSdpSettings("SP3000 Remote","Media remote for Astell&Kern SP3000","SP3000 Remote",BluetoothHidDevice.SUBCLASS1_NONE,HID_DESCRIPTOR);
        setStatus(hid.registerApp(s,null,null,executor,callback) ? "Registering HID remote…" : "Could not register HID remote");
    }

    private void refresh() {
        if (!allowed()) return; bonded.clear(); Set<BluetoothDevice> set = adapter.getBondedDevices(); if (set != null) bonded.addAll(set);
        List<String> labels = new ArrayList<>(); int preferred = 0;
        for (int i=0;i<bonded.size();i++) { BluetoothDevice d=bonded.get(i); String n=name(d); labels.add(n+"\n"+d.getAddress()); if(n.toUpperCase().contains("SP3000")) preferred=i; }
        if(labels.isEmpty()) labels.add("No paired devices — pair SP3000 first");
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); if(!bonded.isEmpty()) spinner.setSelection(preferred);
    }

    private void toggle() {
        if(hid==null || !allowed()) return;
        if(selected!=null && hid.getConnectionState(selected)==BluetoothProfile.STATE_CONNECTED) { hid.disconnect(selected); return; }
        if(bonded.isEmpty()) { Toast.makeText(this,"Pair SP3000 first",Toast.LENGTH_LONG).show(); return; }
        int pos=Math.max(0,spinner.getSelectedItemPosition()); selected=bonded.get(Math.min(pos,bonded.size()-1)); setStatus("Connecting to "+name(selected)+"…"); hid.connect(selected);
    }

    private void discover() {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},REQ_BT); return; }
        Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE); i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300); startActivity(i); setStatus("Phone discoverable — pair it from SP3000 Bluetooth settings");
    }

    private void send(int bit) {
        if(hid==null || selected==null || !allowed() || hid.getConnectionState(selected)!=BluetoothProfile.STATE_CONNECTED) { Toast.makeText(this,"Connect to SP3000 first",Toast.LENGTH_SHORT).show(); return; }
        hid.sendReport(selected,REPORT_ID,new byte[]{(byte)(1<<bit)});
        handler.postDelayed(() -> { if(hid!=null && selected!=null && allowed()) hid.sendReport(selected,REPORT_ID,new byte[]{0}); },30);
    }

    private boolean allowed() { return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED; }
    private String name(BluetoothDevice d) { if(!allowed()) return "Bluetooth device"; String n=d.getName(); return n==null||n.trim().isEmpty()?"Bluetooth device":n; }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(34),dp(20),dp(20)); root.setBackgroundColor(Color.rgb(11,13,16));
        TextView title=txt("SP3000 REMOTE",28,Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title,match());
        TextView sub=txt("Bluetooth HID media controller",14,Color.rgb(160,164,172)); sub.setGravity(Gravity.CENTER); root.addView(sub,match());
        status=txt("Preparing…",14,Color.rgb(208,180,119)); status.setGravity(Gravity.CENTER); status.setPadding(0,dp(18),0,dp(12)); root.addView(status,match());
        spinner=new Spinner(this); root.addView(spinner,match());
        LinearLayout pair=new LinearLayout(this); Button p=btn("Pair new SP3000"), r=btn("Refresh"); p.setOnClickListener(v->discover()); r.setOnClickListener(v->refresh()); pair.addView(p,weight()); pair.addView(r,weight()); root.addView(pair,match());
        connect=btn("Connect"); connect.setEnabled(false); connect.setOnClickListener(v->toggle()); root.addView(connect,match());
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setVisibility(View.INVISIBLE);
        LinearLayout transport=new LinearLayout(this); Button prev=big("⏮",42), play=big("▶  ❚❚",26), next=big("⏭",42); prev.setOnClickListener(v->send(2)); play.setOnClickListener(v->send(0)); next.setOnClickListener(v->send(1)); transport.addView(prev,weight()); transport.addView(play,weight()); transport.addView(next,weight()); controls.addView(transport,match());
        LinearLayout vol=new LinearLayout(this); Button down=big("−",38), mute=big("Mute",18), up=big("+",38); down.setOnClickListener(v->send(5)); mute.setOnClickListener(v->send(6)); up.setOnClickListener(v->send(4)); vol.addView(down,weight()); vol.addView(mute,weight()); vol.addView(up,weight()); controls.addView(vol,match()); root.addView(controls,match());
        TextView hint=txt("First use: Pair new SP3000 → on SP3000 open Settings › Bluetooth and pair your phone → return here → Refresh → Connect.",13,Color.rgb(145,150,158)); hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(24),0,0); root.addView(hint,match()); setContentView(root);
    }

    private TextView txt(String s,int z,int c){ TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);return v; }
    private Button btn(String s){ Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(38,42,48));return b; }
    private Button big(String s,int z){ Button b=btn(s);b.setTextSize(z);b.setMinHeight(dp(88));return b; }
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1f);}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private void setStatus(String s){status.setText(s);}

    @Override protected void onDestroy(){ super.onDestroy(); if(hid!=null && allowed()){ try{hid.unregisterApp();}catch(Exception ignored){} try{adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,hid);}catch(Exception ignored){} } }
}
