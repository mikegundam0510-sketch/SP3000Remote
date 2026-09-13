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
        super.onCreate(b); buildUi(); adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { setStatus("This phone has no Bluetooth adapter"); connect.setEnabled(false); return; }
        ensurePermissions();
    }
    @Override protected void onStart() { super.onStart(); IntentFilter f=new IntentFilter(RemoteService.ACTION_STATE); if(Build.VERSION.SDK_INT>=33) registerReceiver(stateReceiver,f,RECEIVER_NOT_EXPORTED); else registerReceiver(stateReceiver,f); }
    @Override protected void onStop() { super.onStop(); try{unregisterReceiver(stateReceiver);}catch(Exception ignored){} }

    private void ensurePermissions() {
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){ if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT); if(checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_ADVERTISE); }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),REQ_BT);return;} ready();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_BT)ready();}
    private void ready(){if(!adapter.isEnabled()){setStatus("Turn Bluetooth on first");startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}refresh();connect.setEnabled(true);startRemoteService(RemoteService.ACTION_START,null);}
    private void refresh(){if(!allowed())return;bonded.clear();Set<BluetoothDevice> set=adapter.getBondedDevices();if(set!=null)bonded.addAll(set);List<String> labels=new ArrayList<>();int preferred=0;for(int i=0;i<bonded.size();i++){BluetoothDevice d=bonded.get(i);String n=name(d);labels.add(n+"\n"+d.getAddress());if(n.toUpperCase().contains("SP3000"))preferred=i;}if(labels.isEmpty())labels.add("No paired devices — pair SP3000 first");spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));if(!bonded.isEmpty())spinner.setSelection(preferred);}
    private void toggle(){if(RemoteService.isConnected()){startRemoteService(RemoteService.ACTION_DISCONNECT,null);return;}if(bonded.isEmpty()){Toast.makeText(this,"Pair SP3000 first",Toast.LENGTH_LONG).show();return;}int pos=Math.max(0,spinner.getSelectedItemPosition());BluetoothDevice d=bonded.get(Math.min(pos,bonded.size()-1));startRemoteService(RemoteService.ACTION_CONNECT,d.getAddress());}
    private void discover(){if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},REQ_BT);return;}Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300);startActivity(i);setStatus("Phone discoverable — pair it from SP3000 Bluetooth settings");}
    private void command(String action){startRemoteService(action,null);}
    private void startRemoteService(String action,String address){Intent i=new Intent(this,RemoteService.class).setAction(action);if(address!=null)i.putExtra(RemoteService.EXTRA_ADDRESS,address);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private boolean allowed(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private String name(BluetoothDevice d){if(!allowed())return "Bluetooth device";String n=d.getName();return n==null||n.trim().isEmpty()?"Bluetooth device":n;}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(28),dp(18),dp(20));root.setBackgroundColor(Color.rgb(12,14,18));
        TextView title=txt("SP3000 REMOTE",27,Color.WHITE);title.setGravity(Gravity.CENTER);title.setTypeface(null,1);root.addView(title,match());
        TextView sub=txt("Bluetooth remote",13,Color.rgb(142,149,160));sub.setGravity(Gravity.CENTER);sub.setPadding(0,dp(3),0,0);root.addView(sub,match());
        status=txt("Preparing…",14,Color.rgb(214,184,119));status.setGravity(Gravity.CENTER);status.setPadding(dp(10),dp(15),dp(10),dp(15));root.addView(status,match());

        spinner=new Spinner(this);LinearLayout.LayoutParams sp=match();sp.setMargins(0,0,0,dp(8));root.addView(spinner,sp);
        LinearLayout pair=row();Button p=small("Pair SP3000"),r=small("Refresh");p.setOnClickListener(v->discover());r.setOnClickListener(v->refresh());addEqual(pair,p);addEqual(pair,r);root.addView(pair,rowParams());
        connect=wide("Connect");connect.setEnabled(false);connect.setOnClickListener(v->toggle());LinearLayout.LayoutParams cp=match();cp.height=dp(54);cp.setMargins(0,0,0,dp(16));root.addView(connect,cp);

        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setVisibility(View.INVISIBLE);
        controls.addView(section("PLAYBACK"),match());
        LinearLayout transport=row();Button prev=control("⏮",30),play=control("▶  ❚❚",20),next=control("⏭",30);prev.setOnClickListener(v->command(RemoteService.ACTION_PREV));play.setOnClickListener(v->command(RemoteService.ACTION_PLAY_PAUSE));next.setOnClickListener(v->command(RemoteService.ACTION_NEXT));addEqual(transport,prev);addEqual(transport,play);addEqual(transport,next);controls.addView(transport,rowParams());
        controls.addView(section("VOLUME"),match());
        LinearLayout vol=row();Button down=control("−",30),mute=control("Mute",16),up=control("+",30);down.setOnClickListener(v->command(RemoteService.ACTION_VOL_DOWN));mute.setOnClickListener(v->command(RemoteService.ACTION_MUTE));up.setOnClickListener(v->command(RemoteService.ACTION_VOL_UP));addEqual(vol,down);addEqual(vol,mute);addEqual(vol,up);controls.addView(vol,rowParams());
        controls.addView(section("SCREEN"),match());
        LinearLayout screen=row();Button off=control("Screen Off",15),wake=control("Wake Screen",15);off.setOnClickListener(v->command(RemoteService.ACTION_SCREEN_OFF));wake.setOnClickListener(v->command(RemoteService.ACTION_SCREEN_ON));addEqual(screen,off);addEqual(screen,wake);controls.addView(screen,rowParams());
        root.addView(controls,match());
        TextView hint=txt("Screen controls use Bluetooth HID System Sleep / Wake.",12,Color.rgb(126,132,142));hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(14),0,0);root.addView(hint,match());setContentView(root);
    }

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER);return l;}
    private void addEqual(LinearLayout row,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(64),1f);p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);}
    private LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=match();p.setMargins(-dp(4),0,-dp(4),dp(10));return p;}
    private TextView section(String s){TextView v=txt(s,11,Color.rgb(126,132,142));v.setTypeface(null,1);v.setPadding(dp(4),dp(5),0,dp(7));return v;}
    private GradientDrawable bg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),Color.rgb(58,63,72));return g;}
    private Button baseButton(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(6),0,dp(6),0);b.setBackground(bg(Color.rgb(34,38,45),12));return b;}
    private Button control(String s,int z){Button b=baseButton(s);b.setTextSize(z);return b;}
    private Button small(String s){Button b=baseButton(s);b.setTextSize(14);return b;}
    private Button wide(String s){Button b=baseButton(s);b.setTextSize(16);return b;}
    private TextView txt(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);return v;}
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private void setStatus(String s){status.setText(s);}
}
