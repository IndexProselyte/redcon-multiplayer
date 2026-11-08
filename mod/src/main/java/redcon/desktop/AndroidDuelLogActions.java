package redcon.desktop;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;

/** Android 10+ scoped Downloads storage, requiring no storage permission. */
public final class AndroidDuelLogActions implements DuelLogActions.Platform {
    private static boolean started;
    public static synchronized void start() {
        if (started || mojo.Platform.C == null) return;
        started = true;
        DuelAppLog.install(new File(mojo.Platform.C.getFilesDir(), "redcon-recent.log"));
        DuelLogActions.install(new AndroidDuelLogActions());
        DuelAppLog.record("Android " + android.os.Build.VERSION.RELEASE + " | "
            + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, DuelConnectionStatus.NORMAL);
    }
    @Override public void copy(String text) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            ClipboardManager clipboard = (ClipboardManager) mojo.Platform.C.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("REDCON log", text));
            return null;
        });
        mojo.Platform.C.runOnUiThread(task);
        task.get();
    }
    @Override public String save(String text) throws Exception {
        android.content.ContentResolver resolver = mojo.Platform.C.getContentResolver();
        String name = "redcon-log-" + System.currentTimeMillis() + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/REDCON");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("Downloads provider unavailable");
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("Cannot open download");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) < 1) throw new java.io.IOException("Cannot publish download");
        } catch (Exception failure) {
            resolver.delete(uri, null, null);
            throw failure;
        }
        return "Downloads/REDCON/" + name;
    }
    private AndroidDuelLogActions() { }
}
