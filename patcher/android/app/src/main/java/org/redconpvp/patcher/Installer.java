package com.bytes14.redconpvp.patcher;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import java.io.*;
import java.util.Map;

final class Installer {
    static int install(Context context, PatchManifest.Entry entry, Map<String, File> files) throws Exception {
        long size = 0;
        for (PatchManifest.FileSpec spec : entry.outputs) size = Math.addExact(size, spec.size);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(entry.output.packageName);
        params.setSize(size);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            byte[] buffer = new byte[1024 * 1024];
            for (PatchManifest.FileSpec spec : entry.outputs) {
                File file = files.get(spec.name);
                if (file == null) throw new IOException("Missing reconstructed split " + spec.name);
                try (InputStream in = new BufferedInputStream(new FileInputStream(file));
                     OutputStream out = session.openWrite(spec.name, 0, spec.size)) {
                    for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) out.write(buffer, 0, count);
                    session.fsync(out);
                }
            }
            Intent result = new Intent(context, InstallResultReceiver.class).setAction(InstallResultReceiver.ACTION);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (!committed) installer.abandonSession(id);
        }
        context.getSharedPreferences("install", Context.MODE_PRIVATE).edit().putInt("session", id).apply();
        return id;
    }

    static void abandonOwnedSessions(Context context) {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo info : installer.getMySessions()) {
            if (!info.isActive()) installer.abandonSession(info.getSessionId());
        }
    }

    private Installer() { }
}
