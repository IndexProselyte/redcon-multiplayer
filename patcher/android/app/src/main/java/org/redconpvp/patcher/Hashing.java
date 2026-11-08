package com.bytes14.redconpvp.patcher;

import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

final class Hashing {
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = in.read(buffer)) >= 0;) {
            if (count == 0) continue;
            if (out.size() + count > limit) throw new IOException("asset exceeds signed-format limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private Hashing() { }
}
