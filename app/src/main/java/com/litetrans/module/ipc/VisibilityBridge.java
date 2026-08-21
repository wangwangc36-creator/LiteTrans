package com.litetrans.module.ipc;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;

import java.util.List;

/**
 * Android 11+ package visibility can block an arbitrary hooked app from binding to LiteTrans.
 * A URI grant makes the provider-owning package visible to the recipient app by platform design.
 */
public final class VisibilityBridge {
    public static final Uri VISIBILITY_URI = Uri.parse("content://com.litetrans.module.visibility/ping");

    private VisibilityBridge() {}

    public static int grantToInstalledApps(Context context) {
        int granted = 0;
        try {
            List<ApplicationInfo> apps = context.getPackageManager().getInstalledApplications(0);
            String self = context.getPackageName();
            for (ApplicationInfo info : apps) {
                if (info == null || info.packageName == null || self.equals(info.packageName)) continue;
                try {
                    context.grantUriPermission(info.packageName, VISIBILITY_URI,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    granted++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return granted;
    }
}
