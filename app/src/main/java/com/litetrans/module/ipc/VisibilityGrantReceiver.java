package com.litetrans.module.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class VisibilityGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        VisibilityBridge.grantToInstalledApps(context);
    }
}
