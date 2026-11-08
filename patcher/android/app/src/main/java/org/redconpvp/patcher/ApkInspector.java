package com.bytes14.redconpvp.patcher;

import android.content.pm.*;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

final class ApkInspector {
    static void verifyArchive(PackageManager pm, File apk, PatchManifest.PackageSpec expected) throws Exception {
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null) throw new SecurityException("Android cannot parse " + apk.getName());
        if (!expected.packageName.equals(info.packageName)) throw new SecurityException("unexpected package in " + apk.getName());
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (version != expected.versionCode) throw new SecurityException("unexpected version in " + apk.getName());
        if (!expected.certificate.equals(certificate(info))) throw new SecurityException("unexpected signer in " + apk.getName());
    }

    static String installedCertificate(PackageManager pm, String packageName) throws Exception {
        return certificate(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES));
    }

    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length != 1) throw new SecurityException("exactly one APK signer is required");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private ApkInspector() { }
}
