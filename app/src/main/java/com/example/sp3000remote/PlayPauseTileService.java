package com.example.sp3000remote;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class PlayPauseTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel("SP3000 Play/Pause");
            tile.setState(RemoteService.isConnected() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override public void onClick() {
        super.onClick();
        Intent i = new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_PLAY_PAUSE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(RemoteService.isConnected() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
}

