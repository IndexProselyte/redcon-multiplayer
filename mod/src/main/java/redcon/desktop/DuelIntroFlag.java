package redcon.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Whether this install has already seen the Academy intro.
 *
 * <p>Phase 5.5 rule 4: the flag may not live in the campaign profile and may
 * not live in the duel's scratch directory. Not the profile, because a duel
 * must not write the player's save at all — that was P5-014, and it is the one
 * rule the whole phase is built around. Not the scratch directory, because duel
 * mode points REDCON's private storage at a throwaway path and deletes it, so a
 * flag written there would be forgotten and the intro would play every time.
 *
 * <p>So it is a file of the duel's own, beside the campaign directory rather
 * than inside it, written directly rather than through the game's storage.
 * REDCON never reads it, `CampaignSnapshot` never sees it, and it survives the
 * scratch redirect because it does not go through it.</p>
 *
 * <p>Failing to read or write is not an error. An install with an unwritable
 * directory should still play the game; the cost of a lost flag is that the
 * intro is offered again, which is the harmless direction to fail in.</p>
 */
public final class DuelIntroFlag {

    private static final String FILE = "duel-intro-seen";

    /** Overridable so a check can exercise both states without touching real state. */
    private static volatile Path directory;

    private DuelIntroFlag() {
    }

    /** Where the flag lives: beside the campaign profile, never inside it. */
    private static Path file() {
        Path base = directory;
        if (base == null) {
            Path campaign = GameContext.desktopCampaignDirectory();
            Path parent = campaign.getParent();
            base = parent == null ? campaign : parent;
        }
        return base.resolve(FILE);
    }

    /** True once {@link #markSeen()} has succeeded on this install. */
    public static boolean seen() {
        return Files.exists(file());
    }

    /** Record that the intro has played. Best effort. */
    public static void markSeen() {
        Path target = file();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write and rename, so an interrupted write cannot leave a file
            // that exists but means nothing.
            Path temporary = target.resolveSibling(FILE + ".tmp");
            Files.write(temporary, "seen\n".getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Losing the flag replays the intro. That is the safe direction.
        }
    }

    /** Point the flag at a scratch location, for checks. Null restores. */
    static void useDirectoryForCheck(Path scratch) {
        directory = scratch == null ? null : scratch.toAbsolutePath().normalize();
    }

    /** Forget the flag, for checks. */
    static void clearForCheck() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
            // Nothing to do; the check will report what it sees.
        }
    }
}
