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
import android.widget.RemoteViews;
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
    public static final String ACTION_SCREEN_TOGGLE = "com.example.sp3000remote.SCREEN_TOGGLE";
    public static final String ACTION_BACK = "com.example.sp3000remote.BACK";
    public static final String ACTION_SHUFFLE = "com.example.sp3000remote.SHUFFLE";
    public static final String ACTION_REPEAT = "com.example.sp3000remote.REPEAT";

    public static final String ACTION_STATE = "com.example.sp3000remote.STATE";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_PLAYING = "playing";

    private static final String CHANNEL_ID = "dap_remote";
    private static final int NOTIFICATION_ID = 3000;
    private static final int REPORT_MEDIA = 1;
    private static final int REPORT_SYSTEM = 2;
    private static final int REPORT_KEYBOARD = 3;
    private static final int REPORT_CONSUMER_EXT = 4;
    private static final long AUTO_CONNECT_INTERVAL_MS = 5000L;
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
        0x75,0x06,(byte)0x95,0x01,(byte)0x81,0x03,(byte)0xC0,

        0x05,0x01,0x09,0x06,(byte)0xA1,0x01,(byte)0x85,REPORT_KEYBOARD,
        0x05,0x07,0x19,(byte)0xE0,0x29,(byte)0xE7,0x15,0x00,0x25,0x01,
        0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
        (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x03,
        (byte)0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,
        0x05,0x07,0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0,

        0x05,0x0C,0x09,0x01,(byte)0xA1,0x01,(byte)0x85,REPORT_CONSUMER_EXT,
        0x15,0x00,0x26,(byte)0xFF,0x0F,0x19,0x00,0x2A,(byte)0xFF,0x0F,
        0x75,0x10,(byte)0x95,0x01,(byte)0x81,0x00,(byte)0xC0
    };

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice selected;
    private String pendingAddress;
    private String queuedAction;
    private String currentDeviceName = "";
    private boolean manualDisconnect = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor = Runnable::run;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!connected && !manualDisconnect) {
                connectSaved();
                handler.postDelayed(this, AUTO_CONNECT_INTERVAL_MS);
            }
        }
    };

    public static boolean isConnected() { return connected; }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean registered) {
            if (registered) {
                String address = pendingAddress;
                if (address == null) address = getSharedPreferences("remote", MODE_PRIVATE).getString("address", null);
                if (address != null) {
                    connectAddress(address);
                    startAutoConnectLoop();
                } else publishState("Ready — choose DAP and connect", false);
            } else publishState("Bluetooth remote unavailable", false);
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                selected = device;
                currentDeviceName = name(device);
                connected = true;
                manualDisconnect = false;
                handler.removeCallbacks(reconnectRunnable);
                if ((Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) getSharedPreferences("remote", MODE_PRIVATE).edit()
                    .putString("address", device.getAddress())
                    .putString("device_name", currentDeviceName)
                    .apply();
                publishState("Connected", true);
                if (queuedAction != null) {
                    String a = queuedAction;
                    queuedAction = null;
                    handler.postDelayed(() -> executeCommand(a), 180);
                }
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                currentDeviceName = name(device);
                publishState("Connecting…", false);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                publishState(manualDisconnect ? "Disconnected" : "Waiting for DAP…", false);
                if (!manualDisconnect) startAutoConnectLoop();
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
        currentDeviceName = getSharedPreferences("remote", MODE_PRIVATE).getString("device_name", "");
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (adapter != null && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) adapter.getProfileProxy(this, listener, BluetoothProfile.HID_DEVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            manualDisconnect = false;
            startAutoConnectLoop();
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            manualDisconnect = false;
            pendingAddress = intent.getStringExtra(EXTRA_ADDRESS);
            if (hid != null && pendingAddress != null) connectAddress(pendingAddress);
            startAutoConnectLoop();
        } else if (ACTION_DISCONNECT.equals(action)) {
            manualDisconnect = true;
            queuedAction = null;
            handler.removeCallbacks(reconnectRunnable);
            if (hid != null && selected != null && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) hid.disconnect(selected);
        } else if (isCommand(action)) {
            if (connected) executeCommand(action);
            else {
                queuedAction = action;
                manualDisconnect = false;
                publishState("Reconnecting…", false);
                connectSaved();
                startAutoConnectLoop();
            }
        } else {
            manualDisconnect = false;
            startAutoConnectLoop();
        }
        updateNotification();
        return START_STICKY;
    }

    private void startAutoConnectLoop() {
        if (manualDisconnect || connected) return;
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, 1200);
    }

    private boolean isCommand(String action) {
        return ACTION_PLAY_PAUSE.equals(action) || ACTION_NEXT.equals(action) || ACTION_PREV.equals(action)
            || ACTION_VOL_UP.equals(action) || ACTION_VOL_DOWN.equals(action) || ACTION_MUTE.equals(action)
            || ACTION_SCREEN_TOGGLE.equals(action) || ACTION_BACK.equals(action)
            || ACTION_SHUFFLE.equals(action) || ACTION_REPEAT.equals(action);
    }

    private void executeCommand(String action) {
        if (ACTION_PLAY_PAUSE.equals(action)) {
            sendMedia(0);
            boolean playing = getSharedPreferences("remote", MODE_PRIVATE)
                .getBoolean("is_playing", false);
            getSharedPreferences("remote", MODE_PRIVATE).edit()
                .putBoolean("is_playing", !playing).apply();
            publishState(connected ? "Connected" : "Ready", connected);
            RemoteWidgetProvider.updateAll(this, connected);
        }
        else if (ACTION_NEXT.equals(action)) sendMedia(1);
        else if (ACTION_PREV.equals(action)) sendMedia(2);
        else if (ACTION_VOL_UP.equals(action)) sendMedia(4);
        else if (ACTION_VOL_DOWN.equals(action)) sendMedia(5);
        else if (ACTION_MUTE.equals(action)) sendMedia(6);
        else if (ACTION_SCREEN_TOGGLE.equals(action)) sendConsumerUsage(0x0030);
        else if (ACTION_BACK.equals(action)) sendConsumerUsage(0x0224);
        // Consumer Control usages used by some hosts for player shuffle/repeat.
        else if (ACTION_SHUFFLE.equals(action)) sendConsumerUsage(0x00B9);
        else if (ACTION_REPEAT.equals(action)) sendConsumerUsage(0x00BC);
    }

    private void registerHid() {
        if (hid == null || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        BluetoothHidDeviceAppSdpSettings s = new BluetoothHidDeviceAppSdpSettings(
            "DAP Remote", "Bluetooth remote for DAP", "DAP Remote",
            BluetoothHidDevice.SUBCLASS1_NONE, HID_DESCRIPTOR
        );
        hid.registerApp(s, null, null, executor, callback);
    }

    private void connectSaved() {
        if (connected || hid == null || adapter == null || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) || !adapter.isEnabled()) return;
        String address = pendingAddress;
        if (address == null) address = getSharedPreferences("remote", MODE_PRIVATE).getString("address", null);
        if (address != null) connectAddress(address);
        else publishState("Open app once to select your DAP", false);
    }

    private void connectAddress(String address) {
        if ((Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) || adapter == null || hid == null || address == null || !adapter.isEnabled()) return;
        try {
            selected = adapter.getRemoteDevice(address);
            pendingAddress = address;
            currentDeviceName = name(selected);
            publishState("Connecting…", false);
            hid.connect(selected);
        } catch (Exception e) { publishState("Waiting for saved DAP…", false); }
    }

    private void sendMedia(int bit) { sendReport(REPORT_MEDIA, new byte[]{(byte)(1 << bit)}, new byte[]{0}); }

    private void sendConsumerUsage(int usage) {
        byte[] press = new byte[]{(byte)(usage & 0xFF), (byte)((usage >> 8) & 0xFF)};
        sendReport(REPORT_CONSUMER_EXT, press, new byte[]{0,0});
    }

    private void sendReport(int reportId, byte[] press, byte[] release) {
        if (hid == null || selected == null || !connected || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        hid.sendReport(selected, reportId, press);
        handler.postDelayed(() -> {
            if (hid != null && selected != null && connected && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) hid.sendReport(selected, reportId, release);
        }, 20);
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
        String line = connected ? (currentDeviceName.isEmpty() ? "Connected" : "Connected · " + currentDeviceName) : "Auto connect active — waiting for DAP";
        boolean playing = getSharedPreferences("remote", MODE_PRIVATE)
            .getBoolean("is_playing", false);
        RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notification_expanded);
        expanded.setTextViewText(R.id.notification_device_name,
            currentDeviceName.isEmpty() ? "DAP device" : currentDeviceName);
        expanded.setTextViewText(R.id.notification_connection_status,
            connected ? "Connected" : "Disconnected");
        expanded.setTextColor(R.id.notification_connection_status,
            connected ? 0xFF4BE477 : 0xFF9299A3);
        expanded.setImageViewResource(R.id.notification_play,
            playing ? R.drawable.ic_pause : R.drawable.ic_play);
        expanded.setOnClickPendingIntent(R.id.notification_vol_down, commandPi(ACTION_VOL_DOWN, 4));
        expanded.setOnClickPendingIntent(R.id.notification_prev, commandPi(ACTION_PREV, 1));
        expanded.setOnClickPendingIntent(R.id.notification_play, commandPi(ACTION_PLAY_PAUSE, 2));
        expanded.setOnClickPendingIntent(R.id.notification_next, commandPi(ACTION_NEXT, 3));
        expanded.setOnClickPendingIntent(R.id.notification_vol_up, commandPi(ACTION_VOL_UP, 5));
        b.setSmallIcon(R.drawable.ic_dap_notification)
         .setContentTitle("DAP Remote")
         .setContentText(line)
         .setContentIntent(content).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
         .setCategory(Notification.CATEGORY_TRANSPORT)
         .setCustomBigContentView(expanded);
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        RemoteWidgetProvider.updateAll(this, connected);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "DAP Remote", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("DAP lock-screen remote controls and auto connect");
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private void publishState(String text, boolean isConnected) {
        connected = isConnected;
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, text);
        i.putExtra(EXTRA_CONNECTED, isConnected);
        i.putExtra(EXTRA_DEVICE_NAME, currentDeviceName);
        i.putExtra(EXTRA_PLAYING, getSharedPreferences("remote", MODE_PRIVATE)
            .getBoolean("is_playing", false));
        sendBroadcast(i);
        updateNotification();
    }



    private String name(BluetoothDevice d) {
        if (d == null || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return "DAP";
        String n = d.getName(); return n == null || n.trim().isEmpty() ? "DAP" : n;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        connected = false;
        handler.removeCallbacks(reconnectRunnable);
        if (hid != null && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
            try { hid.unregisterApp(); } catch (Exception ignored) {}
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}

