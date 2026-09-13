package com.example.sp3000remote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.Executor;

public class RemoteService extends Service {
    public static final String ACTION_START = "com.example.sp3000remote.START";
    public static final String ACTION_CONNECT = "com.example.sp3000remote.CONNECT";
    public static final String ACTION_DISCONNECT = "com.example.sp3000remote.DISCONNECT";
    public static final String ACTION_PLAY_PAUSE = "com.example.sp3000remote.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.sp3000remote.NEXT";
    public static final String ACTION_PREV = "com.example.sp3000remote.PREV";
    public static final String ACTION_VOL_UP = "com.example.sp3000remote.VOL_UP";
    public static final String ACTION_VOL_DOWN = "com.example.sp3000remote.VOL_DOWN";
    public static final String ACTION_MUTE = "com.example.sp3000remote.MUTE";
    public static final String ACTION_SCREEN_OFF = "com.example.sp3000remote.SCREEN_OFF";
    public static final String ACTION_SCREEN_ON = "com.example.sp3000remote.SCREEN_ON";
    public static final String ACTION_STATE = "com.example.sp3000remote.STATE";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_CONNECTED = "connected";

    private static final String CHANNEL_ID = "sp3000_remote";
    private static final int NOTIFICATION_ID = 3000;
    private static final int REPORT_MEDIA = 1;
    private static final int REPORT_SYSTEM = 2;
    private static volatile boolean connected = false;

    private static final byte[] HID_DESCRIPTOR = new byte[]{
        0x05,0x0C,0x09,0x01,(byte)0xA1,0x01,(byte)0x85,REPORT_MEDIA,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x07,
        0x09,(byte)0xCD,0x09,(byte)0xB5,0x09,(byte)0xB6,0x09,(byte)0xB7,
        0x09,(byte)0xE9,0x09,(byte)0xEA,0x09,(byte)0xE2,
        (byte)0x81,0x02,0x75,0x01,(byte)0x95,0x01,(byte)0x81,0x03,(byte)0xC0,
        0x05,0x01,0x09,(byte)0x80,(byte)0xA1,0x01,(byte)0x85,REPORT_SYSTEM,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x02,
        0x09,(byte)0x82,0x09,(byte)0x83,(byte)0x81,0x02,
        0x75,0x06,(byte)0x95,0x01,(byte)0x81,0x03,(byte)0xC0
    };

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice selected;
    private String pendingAddress;
    private String queuedAction;
    private boolean manualDisconnect = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor = Runnable::run;
    private final Runnable reconnectRunnable = () -> {
        if (!connected && !manualDisconnect) connectSaved();
    };

    public static boolean isConnected() { return connected; }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean registered) {
            if (registered) {
                String address = pendingAddress;
                if (address == null) address = getSharedPreferences("remote", MODE_PRIVATE).getString("address", null);
                if (address != null) connectAddress(address);
                else publishState("Ready — choose SP3000 and connect", false);
            } else publishState("Bluetooth remote unavailable", false);
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                selected = device;
                connected = true;
                manualDisconnect = false;
                handler.removeCallbacks(reconnectRunnable);
                if (allowed()) getSharedPreferences("remote", MODE_PRIVATE).edit().putString("address", device.getAddress()).apply();
                publishState("Connected to " + name(device), true);
                if (queuedAction != null) {
                    String a = queuedAction;
                    queuedAction = null;
                    handler.postDelayed(() -> executeCommand(a), 180);
                }
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                publishState("Connecting to " + name(device) + "…", false);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                publishState(manualDisconnect ? "Disconnected" : "Disconnected — reconnecting…", false);
                if (!manualDisconnect) {
                    handler.removeCallbacks(reconnectRunnable);
                    handler.postDelayed(reconnectRunnable, 2500);
                }
            }
        }
    };

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) { hid = (BluetoothHidDevice) proxy; registerHid(); }
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null; connected = false; publishState("Bluetooth service unavailable", false);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        adapter = BluetoothAdapter.getDefaultAdapter();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (adapter != null && allowed()) adapter.getProfileProxy(this, listener, BluetoothProfile.HID_DEVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            manualDisconnect = false;
            pendingAddress = intent.getStringExtra(EXTRA_ADDRESS);
            if (hid != null && pendingAddress != null) connectAddress(pendingAddress);
        } else if (ACTION_DISCONNECT.equals(action)) {
            manualDisconnect = true;
            queuedAction = null;
            handler.removeCallbacks(reconnectRunnable);
            if (hid != null && selected != null && allowed()) hid.disconnect(selected);
        } else if (isCommand(action)) {
            if (connected) executeCommand(action);
            else {
                queuedAction = action;
                manualDisconnect = false;
                publishState("Reconnecting to send command…", false);
                connectSaved();
            }
        }
        updateNotification();
        return START_STICKY;
    }

    private boolean isCommand(String action) {
        return ACTION_PLAY_PAUSE.equals(action) || ACTION_NEXT.equals(action) || ACTION_PREV.equals(action)
            || ACTION_VOL_UP.equals(action) || ACTION_VOL_DOWN.equals(action) || ACTION_MUTE.equals(action)
            || ACTION_SCREEN_OFF.equals(action) || ACTION_SCREEN_ON.equals(action);
    }

    private void executeCommand(String action) {
        if (ACTION_PLAY_PAUSE.equals(action)) sendMedia(0);
        else if (ACTION_NEXT.equals(action)) sendMedia(1);
        else if (ACTION_PREV.equals(action)) sendMedia(2);
        else if (ACTION_VOL_UP.equals(action)) sendMedia(4);
        else if (ACTION_VOL_DOWN.equals(action)) sendMedia(5);
        else if (ACTION_MUTE.equals(action)) sendMedia(6);
        else if (ACTION_SCREEN_OFF.equals(action)) sendSystem(0);
        else if (ACTION_SCREEN_ON.equals(action)) sendSystem(1);
    }

    private void registerHid() {
        if (hid == null || !allowed()) return;
        BluetoothHidDeviceAppSdpSettings s = new BluetoothHidDeviceAppSdpSettings(
            "SP3000 Remote", "Media remote for Astell&Kern SP3000", "SP3000 Remote",
            BluetoothHidDevice.SUBCLASS1_NONE, HID_DESCRIPTOR
        );
        hid.registerApp(s, null, null, executor, callback);
    }

    private void connectSaved() {
        if (connected || hid == null || adapter == null || !allowed()) return;
        String address = pendingAddress;
        if (address == null) address = getSharedPreferences("remote", MODE_PRIVATE).getString("address", null);
        if (address != null) connectAddress(address);
        else publishState("Open app once to select SP3000", false);
    }

    private void connectAddress(String address) {
        if (!allowed() || adapter == null || hid == null || address == null) return;
        try {
            selected = adapter.getRemoteDevice(address);
            pendingAddress = address;
            publishState("Connecting to " + name(selected) + "…", false);
            hid.connect(selected);
        } catch (Exception e) { publishState("Could not connect to saved SP3000", false); }
    }

    private void sendMedia(int bit) { sendReport(REPORT_MEDIA, (byte)(1 << bit)); }
    private void sendSystem(int bit) { sendReport(REPORT_SYSTEM, (byte)(1 << bit)); }

    private void sendReport(int reportId, byte value) {
        if (hid == null || selected == null || !connected || !allowed()) return;
        hid.sendReport(selected, reportId, new byte[]{value});
        handler.postDelayed(() -> {
            if (hid != null && selected != null && connected && allowed()) hid.sendReport(selected, reportId, new byte[]{0});
        }, 40);
    }

    private PendingIntent commandPi(String action, int requestCode) {
        Intent i = new Intent(this, RemoteService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, i, flags);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 100, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentTitle("SP3000 Remote")
         .setContentText(connected ? "Connected" : "Remote active — auto reconnect enabled")
         .setContentIntent(content).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT)
         .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", commandPi(ACTION_PREV, 1)).build())
         .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, "Play/Pause", commandPi(ACTION_PLAY_PAUSE, 2)).build())
         .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", commandPi(ACTION_NEXT, 3)).build())
         .addAction(new Notification.Action.Builder(android.R.drawable.ic_lock_idle_lock, "Screen Off", commandPi(ACTION_SCREEN_OFF, 4)).build())
         .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Wake", commandPi(ACTION_SCREEN_ON, 5)).build());
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        RemoteWidgetProvider.updateAll(this, connected);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "SP3000 Remote", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("SP3000 lock-screen remote controls");
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private void publishState(String text, boolean isConnected) {
        connected = isConnected;
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, text); i.putExtra(EXTRA_CONNECTED, isConnected); sendBroadcast(i); updateNotification();
    }

    private boolean allowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String name(BluetoothDevice d) {
        if (d == null || !allowed()) return "SP3000";
        String n = d.getName(); return n == null || n.trim().isEmpty() ? "SP3000" : n;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        connected = false;
        handler.removeCallbacks(reconnectRunnable);
        if (hid != null && allowed()) {
            try { hid.unregisterApp(); } catch (Exception ignored) {}
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
