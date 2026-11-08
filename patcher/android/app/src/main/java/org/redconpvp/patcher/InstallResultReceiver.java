package com.bytes14.redconpvp.patcher;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class InstallResultReceiver extends BroadcastReceiver {
    static final String ACTION = "com.bytes14.redconpvp.patcher.INSTALL_RESULT";
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        boolean success = status == PackageInstaller.STATUS_SUCCESS;
        context.getSharedPreferences("install", Context.MODE_PRIVATE).edit()
            .remove("session").putString("result", success ? "REDCON PvP installed successfully."
                : "Installation failed: " + (message == null ? status : message)).apply();
        Toast.makeText(context, success ? "REDCON PvP installed" : "Installation failed", Toast.LENGTH_LONG).show();
    }
}
