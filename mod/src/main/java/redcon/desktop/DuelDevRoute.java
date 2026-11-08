package redcon.desktop;

/**
 * The single-device duel route, which is a development aid and not a feature.
 *
 * <p>REDCON multiplayer is two people on two devices. Until Phase 04 supplies a
 * peer there is nobody to duel, and the Phase 05 loop would be unrunnable and
 * unverifiable. So the shell keeps one route that stands an opponent up
 * locally - an authored fortress under the game's own AI - purely so Build,
 * Commencing, Battle, Result and Rematch can be played, captured and
 * regression-tested on one machine.</p>
 *
 * <p>It is off unless asked for, so a normal build's Multiplayer menu offers
 * only the network list. Enable it with {@code -Dredcon.devOpponent=1} or the
 * {@code dev-opponent} argument to {@link GameWindow}.</p>
 */
public final class DuelDevRoute {

    private static volatile boolean enabled =
        DuelIdentity.flag("redcon.devOpponent");

    private DuelDevRoute() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Turn the development route on for this process. */
    public static void enable() {
        if (DuelIdentity.developerControlsEnabled()) {
            enabled = true;
        }
    }
}
