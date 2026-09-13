package com.example.sp3000remote;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AutoStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean shouldStart = Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            shouldStart = state == BluetoothAdapter.STATE_ON;
        }

        if (!shouldStart) return;
        String saved = context.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("address", null);
        if (saved == null) return;

        Intent service = new Intent(context, RemoteService.class).setAction(RemoteService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
