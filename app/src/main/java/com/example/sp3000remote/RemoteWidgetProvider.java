package com.example.sp3000remote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

public class RemoteWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) manager.updateAppWidget(id, buildViews(context, RemoteService.isConnected()));
    }

    static void updateAll(Context context, boolean connected) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        ComponentName c = new ComponentName(context, RemoteWidgetProvider.class);
        int[] ids = m.getAppWidgetIds(c);
        for (int id : ids) m.updateAppWidget(id, buildViews(context, connected));
    }

    private static RemoteViews buildViews(Context context, boolean connected) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
        v.setTextViewText(R.id.widget_status, connected ? "SP3000 Connected" : "SP3000 Remote");
        v.setOnClickPendingIntent(R.id.widget_prev, servicePi(context, RemoteService.ACTION_PREV, 11));
        v.setOnClickPendingIntent(R.id.widget_play, servicePi(context, RemoteService.ACTION_PLAY_PAUSE, 12));
        v.setOnClickPendingIntent(R.id.widget_next, servicePi(context, RemoteService.ACTION_NEXT, 13));
        v.setOnClickPendingIntent(R.id.widget_down, servicePi(context, RemoteService.ACTION_VOL_DOWN, 14));
        v.setOnClickPendingIntent(R.id.widget_mute, servicePi(context, RemoteService.ACTION_MUTE, 15));
        v.setOnClickPendingIntent(R.id.widget_up, servicePi(context, RemoteService.ACTION_VOL_UP, 16));
        v.setOnClickPendingIntent(R.id.widget_screen_off, servicePi(context, RemoteService.ACTION_SCREEN_OFF, 18));
        v.setOnClickPendingIntent(R.id.widget_screen_on, servicePi(context, RemoteService.ACTION_SCREEN_ON, 19));
        Intent open = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        v.setOnClickPendingIntent(R.id.widget_status, PendingIntent.getActivity(context, 17, open, flags));
        return v;
    }

    private static PendingIntent servicePi(Context c, String action, int code) {
        Intent i = new Intent(c, RemoteService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getForegroundService(c, code, i, flags);
    }
}
