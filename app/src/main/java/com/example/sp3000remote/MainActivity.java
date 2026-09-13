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
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatTask;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (RemoteService.ACTION_STATE.equals(intent.getAction())) {
                String s = intent.getStringExtra(RemoteService.EXTRA_STATE);
                boolean connected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false);
                setStatus(s == null ? "Ready" : s, connected);
                connect.setText(connected ? "Disconnect" : "Connect");
                controls.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); buildUi(); adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { setStatus("This phone has no Bluetooth adapter", false); connect.setEnabled(false); return; }
        ensurePermissions();
    }
    @Override protected void onStart() { super.onStart(); IntentFilter f=new IntentFilter(RemoteService.ACTION_STATE); if(Build.VERSION.SDK_INT>=33) registerReceiver(stateReceiver,f,RECEIVER_NOT_EXPORTED); else registerReceiver(stateReceiver,f); }
    @Override protected void onStop() { super.onStop(); stopRepeat(); try{unregisterReceiver(stateReceiver);}catch(Exception ignored){} }

    private void ensurePermissions() {
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){ if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT); if(checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_ADVERTISE); }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),REQ_BT);return;} ready();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_BT)ready();}
    private void ready(){if(!adapter.isEnabled()){setStatus("Turn Bluetooth on first",false);startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}refresh();connect.setEnabled(true);controls.setVisibility(View.VISIBLE);startRemoteService(RemoteService.ACTION_START,null);}
    private void refresh(){if(!allowed())return;bonded.clear();Set<BluetoothDevice> set=adapter.getBondedDevices();if(set!=null)bonded.addAll(set);List<String> labels=new ArrayList<>();int preferred=0;for(int i=0;i<bonded.size();i++){BluetoothDevice d=bonded.get(i);String n=name(d);labels.add(n+"\n"+d.getAddress());if(n.toUpperCase().contains("SP3000"))preferred=i;}if(labels.isEmpty())labels.add("No paired devices — pair DAP first");spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));if(!bonded.isEmpty())spinner.setSelection(preferred);}
    private void toggle(){if(RemoteService.isConnected()){startRemoteService(RemoteService.ACTION_DISCONNECT,null);return;}if(bonded.isEmpty()){Toast.makeText(this,"Pair your DAP first",Toast.LENGTH_LONG).show();return;}int pos=Math.max(0,spinner.getSelectedItemPosition());BluetoothDevice d=bonded.get(Math.min(pos,bonded.size()-1));startRemoteService(RemoteService.ACTION_CONNECT,d.getAddress());}
    private void discover(){if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},REQ_BT);return;}Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300);startActivity(i);setStatus("Phone discoverable — pair it from your DAP Bluetooth settings",false);}
    private void command(String action){startRemoteService(action,null);}
    private void startRemoteService(String action,String address){Intent i=new Intent(this,RemoteService.class).setAction(action);if(address!=null)i.putExtra(RemoteService.EXTRA_ADDRESS,address);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private boolean allowed(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private String name(BluetoothDevice d){if(!allowed())return "Bluetooth device";String n=d.getName();return n==null||n.trim().isEmpty()?"Bluetooth device":n;}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(26),dp(18),dp(22));root.setBackgroundColor(Color.rgb(10,12,16));
        TextView title=txt("DAP REMOTE",28,Color.WHITE);title.setGravity(Gravity.CENTER);title.setTypeface(null,1);root.addView(title,match());
        TextView sub=txt("Bluetooth HID Controller",12,Color.rgb(132,140,153));sub.setGravity(Gravity.CENTER);sub.setPadding(0,dp(4),0,dp(14));root.addView(sub,match());

        status=txt("Preparing…",14,Color.WHITE);status.setGravity(Gravity.CENTER);status.setTypeface(null,1);status.setPadding(dp(12),dp(12),dp(12),dp(12));status.setBackground(bg(Color.rgb(31,35,42),16,Color.rgb(57,63,74)));LinearLayout.LayoutParams st=match();st.setMargins(0,0,0,dp(12));root.addView(status,st);

        spinner=new Spinner(this);LinearLayout.LayoutParams sp=match();sp.setMargins(0,0,0,dp(10));root.addView(spinner,sp);
        LinearLayout pair=row();Button p=small("Pair DAP"),r=small("Refresh devices");p.setOnClickListener(v->discover());r.setOnClickListener(v->refresh());addEqual(pair,p,52);addEqual(pair,r,52);root.addView(pair,rowParams(10));
        connect=wide("Connect");connect.setEnabled(false);connect.setOnClickListener(v->toggle());LinearLayout.LayoutParams cp=match();cp.height=dp(54);cp.setMargins(0,0,0,dp(14));root.addView(connect,cp);

        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setVisibility(View.VISIBLE);
        controls.addView(section("PLAYBACK"),match());
        LinearLayout transport=row();Button prev=control("⏮",28),play=control("▶  ❚❚",18),next=control("⏭",28);prev.setOnClickListener(v->command(RemoteService.ACTION_PREV));play.setOnClickListener(v->command(RemoteService.ACTION_PLAY_PAUSE));next.setOnClickListener(v->command(RemoteService.ACTION_NEXT));addEqual(transport,prev,64);addEqual(transport,play,64);addEqual(transport,next,64);controls.addView(transport,rowParams(12));

        controls.addView(section("VOLUME"),match());
        LinearLayout vol=row();Button down=control("−",30),mute=control("Mute",15),up=control("+",30);mute.setOnClickListener(v->command(RemoteService.ACTION_MUTE));enableRepeat(down,RemoteService.ACTION_VOL_DOWN);enableRepeat(up,RemoteService.ACTION_VOL_UP);addEqual(vol,down,60);addEqual(vol,mute,60);addEqual(vol,up,60);controls.addView(vol,rowParams(12));

        controls.addView(section("SYSTEM"),match());
        LinearLayout system=row();Button screen=control("Screen On / Off",14),back=control("Back",16);screen.setOnClickListener(v->command(RemoteService.ACTION_SCREEN_TOGGLE));back.setOnClickListener(v->command(RemoteService.ACTION_BACK));addEqual(system,screen,58);addEqual(system,back,58);controls.addView(system,rowParams(8));
        root.addView(controls,match());

        TextView hint=txt("Tap − / + = exactly 1 step. Hold for continuous volume. Screen On / Off and Back use the confirmed BR01-compatible commands.",12,Color.rgb(124,131,143));hint.setGravity(Gravity.CENTER);hint.setPadding(dp(6),dp(10),dp(6),0);root.addView(hint,match());setContentView(root);
    }

    private void enableRepeat(Button b,String action){
        b.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                command(action);
                repeatTask=new Runnable(){@Override public void run(){command(action);repeatHandler.postDelayed(this,170);}};
                repeatHandler.postDelayed(repeatTask,450);
            }else if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                stopRepeat();
            }
            return true;
        });
    }
    private void stopRepeat(){if(repeatTask!=null){repeatHandler.removeCallbacks(repeatTask);repeatTask=null;}}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER);return l;}
    private void addEqual(LinearLayout row,Button b,int h){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(h),1f);p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);}
    private LinearLayout.LayoutParams rowParams(int bottom){LinearLayout.LayoutParams p=match();p.setMargins(-dp(4),0,-dp(4),dp(bottom));return p;}
    private TextView section(String s){TextView v=txt(s,11,Color.rgb(126,132,142));v.setTypeface(null,1);v.setPadding(dp(4),dp(3),0,dp(7));return v;}
    private GradientDrawable bg(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private Button baseButton(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(5),0,dp(5),0);b.setBackground(bg(Color.rgb(33,37,44),14,Color.rgb(58,64,74)));return b;}
    private Button control(String s,int z){Button b=baseButton(s);b.setTextSize(z);return b;}
    private Button small(String s){Button b=baseButton(s);b.setTextSize(14);return b;}
    private Button wide(String s){Button b=baseButton(s);b.setTextSize(16);return b;}
    private TextView txt(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);return v;}
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private void setStatus(String s,boolean connected){status.setText((connected?"●  ":"○  ")+s);status.setTextColor(connected?Color.rgb(125,218,151):Color.rgb(214,184,119));}
}
