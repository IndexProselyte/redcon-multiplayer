package redcon.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Device-local multiplayer preferences, outside campaign and duel scratch saves. */
public final class DuelPlayerProfile {
    private static Path checkDirectory;
    private static Path loaded;
    private static String name;

    private static Path file() {
        Path campaign = GameContext.desktopCampaignDirectory();
        Path base = checkDirectory != null ? checkDirectory
            : campaign.resolveSibling(campaign.getFileName() + "-duel-player");
        return base.resolve("name.txt");
    }

    public static synchronized String name() {
        Path file = file();
        if (!file.equals(loaded)) {
            loaded = file;
            name = null;
            try {
                if (Files.exists(file)) name = validate(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException failure) {
                DuelAppLog.error("Read multiplayer name", failure);
            }
        }
        return name;
    }

    public static String validate(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 24
                || clean.getBytes(StandardCharsets.UTF_8).length > 64)
            throw new IllegalArgumentException("Use 1–24 characters (up to 64 UTF-8 bytes).");
        for (int at = 0; at < clean.length();) {
            int code = clean.codePointAt(at);
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT
                    || Character.getType(code) == Character.SURROGATE)
                throw new IllegalArgumentException("Use a single line without control characters.");
            at += Character.charCount(code);
        }
        return clean;
    }

    public static synchronized void save(String value) throws IOException {
        String clean = validate(value);
        Path file = file(), temporary = file.resolveSibling("name.tmp");
        Files.createDirectories(file.getParent());
        Files.write(temporary, clean.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        loaded = file;
        name = clean;
    }

    static synchronized void useDirectoryForCheck(Path directory) {
        checkDirectory = directory;
        loaded = null;
        name = null;
    }
    private DuelPlayerProfile() { }
}
