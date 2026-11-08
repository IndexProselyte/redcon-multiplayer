package com.bytes14.redconpvp.patcher;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;

final class PatchManifest {
    static final int PATCHER_VERSION = 1;
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    final JSONObject root;
    final Limits limits;
    final List<Entry> entries;

    static PatchManifest load(Context context) throws Exception {
        byte[] manifest;
        byte[] signature;
        byte[] publicKey;
        try (InputStream in = context.getAssets().open("manifest.json")) { manifest = Hashing.readBounded(in, 1024 * 1024); }
        try (InputStream in = context.getAssets().open("manifest.sig")) { signature = Hashing.readBounded(in, 1024); }
        try (InputStream in = context.getAssets().open("manifest-public-key.der")) { publicKey = Hashing.readBounded(in, 1024); }
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(manifest);
        if (!verifier.verify(signature)) throw new SecurityException("patch manifest signature is invalid");
        return new PatchManifest(new JSONObject(new String(manifest, java.nio.charset.StandardCharsets.UTF_8)));
    }

    PatchManifest(JSONObject root) throws JSONException {
        this.root = root;
        if (root.getInt("formatVersion") != 1) fail("unsupported manifest format");
        if (root.getInt("minimumPatcherVersion") > PATCHER_VERSION) fail("this patcher is too old for its bundle");
        JSONObject supported = root.getJSONObject("supported");
        if (supported.getInt("minSdk") < 29) fail("invalid minimum SDK");
        JSONArray abis = supported.getJSONArray("abis");
        if (abis.length() != 1 || !"arm64-v8a".equals(abis.getString(0))) fail("unsupported ABI declaration");
        limits = new Limits(root.getJSONObject("limits"));
        JSONArray array = root.getJSONArray("entries");
        if (array.length() < 1 || array.length() > 16) fail("invalid entry count");
        List<Entry> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Entry entry = new Entry(array.getJSONObject(i), limits);
            if (!ids.add(entry.id)) fail("duplicate entry ID");
            parsed.add(entry);
        }
        entries = Collections.unmodifiableList(parsed);
    }

    Entry match(Map<String, File> candidates) throws Exception {
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            boolean match = candidates.size() == entry.inputs.size();
            for (FileSpec input : entry.inputs) {
                File file = candidates.get(input.name);
                match &= file != null && file.length() == input.size && input.sha256.equals(Hashing.sha256(file));
            }
            if (match) matches.add(entry);
        }
        if (matches.size() != 1) throw new IOException("This is not the exact supported REDCON base/split set.");
        return matches.get(0);
    }

    static final class Limits {
        final long maxInput, maxPatch, maxOutput, minFree;
        final int sourceWindow, inputWindow;
        Limits(JSONObject value) throws JSONException {
            maxInput = bounded(value, "maxInputBytes", 1, 256L << 20);
            maxPatch = bounded(value, "maxPatchBytes", 1, 64L << 20);
            maxOutput = bounded(value, "maxOutputBytes", 1, 256L << 20);
            minFree = bounded(value, "minFreeSpaceBytes", 1, 1L << 30);
            sourceWindow = (int) bounded(value, "sourceWindowBytes", 1L << 20, 16L << 20);
            inputWindow = (int) bounded(value, "inputWindowBytes", 64L << 10, 1L << 20);
        }
    }

    static class FileSpec {
        final String name, sha256;
        final long size;
        FileSpec(JSONObject value, long maximum) throws JSONException {
            name = safe(value.getString("name"));
            size = bounded(value, "size", 1, maximum);
            sha256 = value.getString("sha256");
            if (!sha256.matches("[0-9a-f]{64}")) fail("invalid SHA-256");
        }
    }

    static final class PackageSpec {
        final String packageName, versionName, certificate;
        final long versionCode;
        final List<FileSpec> files;
        PackageSpec(JSONObject value, long maximum) throws JSONException {
            packageName = value.getString("packageName");
            versionName = value.getString("versionName");
            versionCode = bounded(value, "versionCode", 1, Integer.MAX_VALUE);
            certificate = value.getString("certificateSha256");
            if (!certificate.matches("[0-9a-f]{64}")) fail("invalid certificate hash");
            JSONArray array = value.getJSONArray("files");
            if (array.length() < 2 || array.length() > 16) fail("incomplete split set");
            List<FileSpec> parsed = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                FileSpec file = new FileSpec(array.getJSONObject(i), maximum);
                if (!names.add(file.name)) fail("duplicate package filename");
                parsed.add(file);
            }
            files = Collections.unmodifiableList(parsed);
        }
    }

    static final class PatchSpec extends FileSpec {
        final String source, target;
        PatchSpec(JSONObject value, long maximum) throws JSONException {
            super(value, maximum);
            source = safe(value.getString("source"));
            target = safe(value.getString("target"));
            if (!"vcdiff-rfc3284-xdelta3-3.2.0".equals(value.getString("format"))) fail("unsupported patch codec");
        }
    }

    static final class Entry {
        final String id;
        final PackageSpec input, output;
        final List<FileSpec> inputs, outputs;
        final List<PatchSpec> patches;
        Entry(JSONObject value, Limits limits) throws JSONException {
            id = safe(value.getString("id"));
            input = new PackageSpec(value.getJSONObject("input"), limits.maxInput);
            output = new PackageSpec(value.getJSONObject("output"), limits.maxOutput);
            inputs = input.files;
            outputs = output.files;
            Map<String, FileSpec> in = index(inputs), out = index(outputs);
            JSONArray array = value.getJSONArray("patches");
            List<PatchSpec> parsed = new ArrayList<>();
            Set<String> names = new HashSet<>(), targets = new HashSet<>();
            long total = 0;
            for (int i = 0; i < array.length(); i++) {
                PatchSpec patch = new PatchSpec(array.getJSONObject(i), limits.maxPatch);
                if (!names.add(patch.name) || !targets.add(patch.target)) fail("duplicate patch mapping");
                if (!in.containsKey(patch.source) || !out.containsKey(patch.target)) fail("patch escapes entry");
                total = Math.addExact(total, patch.size);
                parsed.add(patch);
            }
            if (!targets.equals(out.keySet()) || total > limits.maxPatch) fail("patch set is incomplete or excessive");
            patches = Collections.unmodifiableList(parsed);
        }
    }

    private static Map<String, FileSpec> index(List<FileSpec> files) throws JSONException {
        Map<String, FileSpec> result = new HashMap<>();
        for (FileSpec file : files) if (result.put(file.name, file) != null) fail("duplicate file");
        return result;
    }
    private static String safe(String value) throws JSONException {
        if (!SAFE.matcher(value).matches()) fail("unsafe filename");
        return value;
    }
    private static long bounded(JSONObject value, String name, long min, long max) throws JSONException {
        long number = value.getLong(name);
        if (number < min || number > max) fail("invalid " + name);
        return number;
    }
    private static void fail(String message) throws JSONException { throw new JSONException(message); }
}
