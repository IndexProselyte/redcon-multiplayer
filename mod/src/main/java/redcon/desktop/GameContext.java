package redcon.desktop;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Process-wide mode state shared by the desktop shell and the native screens.
 *
 * <p>Campaign is deliberately the default. Multiplayer screens opt into duel
 * mode only while their activity stack is active and must restore campaign on
 * their way back to the title menu. Keeping that choice in one place prevents
 * duel conditionals from leaking throughout recovered campaign code.</p>
 */
public final class GameContext {

    private static GameMode mode = GameMode.CAMPAIGN;

    private GameContext() {
    }

    public static synchronized GameMode mode() {
        return mode;
    }

    public static synchronized boolean isDuel() {
        return mode == GameMode.DUEL;
    }

    public static synchronized void enterDuel() {
        mode = GameMode.DUEL;
        // Arming a duel battle runs the campaign's own mission start, which
        // flushes progress to disk. Point private storage somewhere the player
        // does not care about for as long as the duel lasts.
        invokeDesktopStorage("redirectToScratch", new Class<?>[] { Path.class },
            new Object[] { desktopCampaignDirectory().resolveSibling("duel-scratch") });
    }

    public static synchronized void enterCampaign() {
        mode = GameMode.CAMPAIGN;
        invokeDesktopStorage("restoreCampaignStorage", new Class<?>[0], new Object[0]);
    }

    /** Desktop owns a replaceable file bridge; Android keeps its native Context. */
    static Path desktopCampaignDirectory() {
        try {
            Class<?> bridge = Class.forName("redcon.desktop.DesktopMainActivity");
            Method method = bridge.getMethod("campaignDirectory");
            return (Path) method.invoke(null);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            if (mojo.Platform.C == null) {
                return Paths.get(".").toAbsolutePath().normalize();
            }
            return mojo.Platform.C.getFilesDir().toPath().toAbsolutePath().normalize();
        }
    }

    private static void invokeDesktopStorage(String name, Class<?>[] types, Object[] values) {
        try {
            Class<?> bridge = Class.forName("redcon.desktop.DesktopMainActivity");
            bridge.getMethod(name, types).invoke(null, values);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // Android uses its real Context. DuelBuildProfile still restores
            // every in-memory profile word it temporarily changes.
        }
    }
}
