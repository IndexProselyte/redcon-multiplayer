package com.bytes14.redconpvp.patcher;

import android.content.Context;
import java.io.*;
import java.util.*;

final class PatchEngine {
    static { System.loadLibrary("redconpatch"); }
    private static native int decode(String source, String patch, String output, long maximumOutput,
                                     int sourceBlock, int inputWindow);
    static native void cancel();

    static Map<String, File> copyAndMatch(Context context, List<File> sources,
                                          PatchManifest manifest) throws Exception {
        File root = privateDirectory(context, "inputs");
        clear(root);
        if (!root.mkdirs()) throw new IOException("Cannot prepare private input storage");
        Map<String, File> candidates = new HashMap<>();
        long copied = 0;
        for (File source : sources) {
            if (!source.isFile()) throw new IOException("Original file disappeared during selection");
            String name = expectedName(source, manifest);
            // Google Play may install locale/density resource splits in
            // addition to the signed base and ABI inputs this bundle uses.
            // They are package-manager-owned files, not patch inputs.
            if (name == null) continue;
            copied = Math.addExact(copied, source.length());
            if (copied > manifest.limits.maxInput) throw new IOException("Selected files exceed input limit");
            File target = new File(root, name);
            if (candidates.put(name, target) != null) throw new IOException("duplicate original file");
            copy(new FileInputStream(source), target, manifest.limits.maxInput);
        }
        return candidates;
    }

    static Map<String, File> classify(List<File> files, PatchManifest manifest) throws Exception {
        Map<String, File> candidates = new HashMap<>();
        for (File file : files) {
            String name = expectedName(file, manifest);
            if (name == null || candidates.put(name, file) != null) throw new IOException("unsupported or duplicate original file");
        }
        return candidates;
    }

    private static String expectedName(File file, PatchManifest manifest) throws Exception {
        String hash = Hashing.sha256(file);
        String name = null;
        for (PatchManifest.Entry entry : manifest.entries) {
            for (PatchManifest.FileSpec expected : entry.inputs) {
                if (file.length() == expected.size && hash.equals(expected.sha256)) {
                    if (name != null && !name.equals(expected.name)) throw new IOException("ambiguous original file");
                    name = expected.name;
                }
            }
        }
        return name;
    }

    static Map<String, File> apply(Context context, PatchManifest manifest, PatchManifest.Entry entry,
                                   Map<String, File> inputs) throws Exception {
        long outputBytes = 0;
        for (PatchManifest.FileSpec output : entry.outputs) outputBytes = Math.addExact(outputBytes, output.size);
        File root = privateDirectory(context, "outputs");
        clear(root);
        if (!root.mkdirs()) throw new IOException("Cannot prepare private output storage");
        if (root.getUsableSpace() < outputBytes + manifest.limits.minFree) throw new IOException("Not enough free storage");
        Map<String, PatchManifest.FileSpec> expected = new HashMap<>();
        for (PatchManifest.FileSpec output : entry.outputs) expected.put(output.name, output);
        Map<String, File> outputs = new HashMap<>();
        for (PatchManifest.PatchSpec patchSpec : entry.patches) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Patching cancelled");
            File patch = new File(root, patchSpec.name);
            try (InputStream asset = context.getAssets().open(patchSpec.name)) {
                copy(asset, patch, manifest.limits.maxPatch);
            }
            if (patch.length() != patchSpec.size || !Hashing.sha256(patch).equals(patchSpec.sha256))
                throw new SecurityException("Bundled patch failed verification");
            File output = new File(root, patchSpec.target + ".partial");
            int result = decode(inputs.get(patchSpec.source).getCanonicalPath(), patch.getCanonicalPath(),
                                output.getCanonicalPath(), expected.get(patchSpec.target).size,
                                manifest.limits.sourceWindow, manifest.limits.inputWindow);
            if (result != 0) throw new IOException(result == -999 ? "Patching cancelled" : "VCDIFF decode failed (" + result + ")");
            PatchManifest.FileSpec fileSpec = expected.get(patchSpec.target);
            if (output.length() != fileSpec.size || !Hashing.sha256(output).equals(fileSpec.sha256))
                throw new SecurityException("Reconstructed APK failed exact verification");
            if ("base.apk".equals(patchSpec.target))
                ApkInspector.verifyArchive(context.getPackageManager(), output, entry.output);
            File complete = new File(root, patchSpec.target);
            if (!output.renameTo(complete)) throw new IOException("Cannot finalize reconstructed APK");
            outputs.put(patchSpec.target, complete);
            if (!patch.delete()) throw new IOException("Cannot remove temporary patch");
        }
        if (outputs.size() != entry.outputs.size()) throw new SecurityException("Incomplete output split set");
        return outputs;
    }

    static File privateDirectory(Context context, String name) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), "patch-work");
        File child = new File(root, name).getCanonicalFile();
        if (!root.getCanonicalFile().equals(child.getParentFile())) throw new IOException("Unsafe private directory");
        return child;
    }

    static void clear(File value) throws IOException {
        if (!value.exists()) return;
        File root = value.getCanonicalFile();
        File allowed = new File(value.getParentFile(), value.getName()).getCanonicalFile();
        if (!root.equals(allowed) || !"patch-work".equals(root.getParentFile().getName())) throw new IOException("Unsafe cleanup target");
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) clearNested(child, root);
            else if (!child.delete()) throw new IOException("Cannot remove temporary file");
        }
        if (!root.delete()) throw new IOException("Cannot remove temporary directory");
    }

    private static void clearNested(File value, File boundary) throws IOException {
        File canonical = value.getCanonicalFile();
        if (!canonical.toPath().startsWith(boundary.getCanonicalFile().toPath())) throw new IOException("Unsafe nested cleanup target");
        File[] children = canonical.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) clearNested(child, boundary);
            else if (!child.delete()) throw new IOException("Cannot remove temporary file");
        }
        if (!canonical.delete()) throw new IOException("Cannot remove temporary directory");
    }

    static void copy(InputStream raw, File target, long maximum) throws IOException {
        long total = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = new BufferedInputStream(raw); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            for (int count; (count = in.read(buffer)) >= 0;) {
                if (count == 0) continue;
                total = Math.addExact(total, count);
                if (total > maximum) throw new IOException("File exceeds signed limit");
                out.write(buffer, 0, count);
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("File size overflow", overflow);
        }
    }

    private PatchEngine() { }
}
