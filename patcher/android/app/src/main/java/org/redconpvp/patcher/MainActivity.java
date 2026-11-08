package com.bytes14.redconpvp.patcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK_APKS = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> job;
    private PatchManifest manifest;
    private PatchManifest.Entry entry;
    private Map<String, File> inputs, outputs;
    private TextView status;
    private Button installedButton, filesButton, createButton, cancelButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        try {
            manifest = PatchManifest.load(this);
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
                throw new IOException("This release supports ARM64 Android devices only.");
            Installer.abandonOwnedSessions(this);
            setStatus("Ready. Supported original: REDCON 2.2.0 with its ARM64 split.\n\n"
                + "Patching stays on this phone. The result installs as a separate unofficial app; the official game and its saves are not changed.");
            restorePrivateInputs();
        } catch (Exception failure) {
            fatal("The signed patch bundle cannot be used: " + failure.getMessage());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String result = getSharedPreferences("install", MODE_PRIVATE).getString("result", null);
        if (result != null) {
            getSharedPreferences("install", MODE_PRIVATE).edit().remove("result").apply();
            setStatus(result);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(244, 241, 232));
        TextView title = new TextView(this);
        title.setText("REDCON PvP Patcher"); title.setTextSize(28); title.setTextColor(Color.rgb(31, 35, 39));
        root.addView(title, full(dp(54)));
        status = new TextView(this);
        status.setTextSize(16); status.setTextColor(Color.rgb(45, 49, 53)); status.setPadding(0, dp(8), 0, dp(18));
        root.addView(status, full(-2));
        installedButton = button("Use installed REDCON", v -> useInstalled());
        filesButton = button("Choose original game files", v -> chooseFiles());
        createButton = button("Create and install PvP", v -> createAndInstall());
        cancelButton = button("Cancel patching", v -> cancel());
        cancelButton.setVisibility(View.GONE);
        root.addView(installedButton, full(dp(54)));
        root.addView(filesButton, full(dp(54)));
        root.addView(createButton, full(dp(54)));
        root.addView(cancelButton, full(dp(54)));
        scroll.addView(root); setContentView(scroll);
        createButton.setEnabled(false);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setOnClickListener(listener); return button;
    }
    private LinearLayout.LayoutParams full(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height); params.bottomMargin = dp(10); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void useInstalled() {
        run("Reading the installed official game…", () -> {
            ApplicationInfo info = getPackageManager().getApplicationInfo("net.hexage.redcon", 0);
            List<File> files = new ArrayList<>(); files.add(new File(info.sourceDir));
            if (info.splitSourceDirs != null) for (String split : info.splitSourceDirs) files.add(new File(split));
            Map<String, File> selected = PatchEngine.copyAndMatch(this, files, manifest);
            accept(selected);
            ui(() -> setStatus("Supported installed REDCON verified. Base and ARM64 split are ready."));
        });
    }

    private void chooseFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_APKS);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_APKS || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        run("Copying and checking selected files…", () -> {
            File root = PatchEngine.privateDirectory(this, "inputs"); PatchEngine.clear(root);
            if (!root.mkdirs()) throw new IOException("Cannot prepare private input storage");
            List<File> files = new ArrayList<>(); long total = 0;
            for (int i = 0; i < uris.size(); i++) {
                File target = new File(root, "selected-" + i + ".apk");
                try (InputStream in = getContentResolver().openInputStream(uris.get(i))) {
                    if (in == null) throw new IOException("A selected file cannot be opened");
                    PatchEngine.copy(in, target, manifest.limits.maxInput - total);
                }
                total = Math.addExact(total, target.length()); files.add(target);
            }
            Map<String, File> selected = PatchEngine.classify(files, manifest);
            accept(selected);
            ui(() -> setStatus("Supported original files verified. Base and ARM64 split are ready."));
        });
    }

    private void accept(Map<String, File> selected) throws Exception {
        PatchManifest.Entry matched = manifest.match(selected);
        File base = selected.get("base.apk");
        if (base == null) throw new SecurityException("The supported base APK is missing");
        // PackageManager cannot parse a split archive in isolation. Exact split
        // hashes are already bound by the signed manifest; package identity,
        // version and signer are verified from the matching base archive.
        ApkInspector.verifyArchive(getPackageManager(), base, matched.input);
        entry = matched; inputs = selected; outputs = null;
        ui(() -> createButton.setEnabled(true));
    }

    private void restorePrivateInputs() {
        try {
            File root = PatchEngine.privateDirectory(this, "inputs"); File[] found = root.listFiles((dir, name) -> name.endsWith(".apk"));
            if (found != null && found.length > 0) accept(PatchEngine.classify(Arrays.asList(found), manifest));
        } catch (Exception ignored) { inputs = null; entry = null; }
    }

    private void createAndInstall() {
        if (entry == null || inputs == null) return;
        run("Reconstructing the exact signed PvP APK set…", () -> {
            checkExistingInstall();
            outputs = PatchEngine.apply(this, manifest, entry, inputs);
            ui(() -> setStatus("Reconstruction verified. Preparing Android's install confirmation…"));
            if (!getPackageManager().canRequestPackageInstalls()) {
                ui(() -> {
                    setStatus("Allow installs from this patcher in Android settings, then tap Create and install PvP again.");
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                });
                return;
            }
            Installer.install(this, entry, outputs);
        });
    }

    private void checkExistingInstall() throws Exception {
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(entry.output.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (!entry.output.certificate.equals(ApkInspector.installedCertificate(getPackageManager(), entry.output.packageName)))
                throw new SecurityException("An existing REDCON PvP uses a different signer. It will not be removed automatically.");
            if (installed.getLongVersionCode() > entry.output.versionCode)
                throw new SecurityException("A newer REDCON PvP is already installed; downgrade is blocked.");
        } catch (PackageManager.NameNotFoundException absent) { /* New install. */ }
    }

    private void cancel() { PatchEngine.cancel(); if (job != null) job.cancel(true); }

    private void run(String message, Task task) {
        if (job != null && !job.isDone()) return;
        setBusy(true); setStatus(message);
        job = worker.submit(() -> {
            try { task.run(); }
            catch (Throwable failure) { ui(() -> setStatus("Cannot continue: " + safeMessage(failure))); }
            finally { ui(() -> setBusy(false)); }
        });
    }

    private String safeMessage(Throwable failure) {
        String value = failure.getMessage(); return value == null || value.trim().isEmpty() ? failure.getClass().getSimpleName() : value;
    }
    private void setBusy(boolean busy) {
        installedButton.setEnabled(!busy && manifest != null); filesButton.setEnabled(!busy && manifest != null);
        createButton.setEnabled(!busy && entry != null); cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    private void fatal(String message) { setStatus(message); installedButton.setEnabled(false); filesButton.setEnabled(false); createButton.setEnabled(false); }
    private void setStatus(String message) { status.setText(message); }
    private void ui(Runnable action) { runOnUiThread(action); }
    @Override protected void onDestroy() { if (isFinishing()) { PatchEngine.cancel(); worker.shutdownNow(); } super.onDestroy(); }
    private interface Task { void run() throws Exception; }
}
