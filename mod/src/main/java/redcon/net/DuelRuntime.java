package redcon.net;

import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.duel.Order;

/**
 * The product lifecycle's one view of a duel, whatever is carrying the events.
 *
 * <p>Phase 04's rule is that every local action returns through the committed
 * consumer, so the product shell must not be able to tell an in-process peer
 * from a networked one. This interface is that boundary: {@code DuelBuildFlow}
 * and {@code DuelBattleFlow} hold a {@code DuelRuntime}, never a transport.</p>
 *
 * <p>Two facts about the peer are visible because the shell genuinely needs
 * them. {@link #localPlayer()} decides which fortress this device drives and
 * therefore which events it may author; {@link #isHost()} decides whether this
 * device may assign phase clocks and commit round results. Everything else -
 * ordering, validation, delivery - stays behind the runtime.</p>
 */
public interface DuelRuntime extends AutoCloseable {

    /** Why a duel stopped being playable, or {@link #LIVE} while it is. */
    enum Link {
        /** Events are flowing. */
        LIVE,
        /** The peer left explicitly; the session carries the reason. */
        PEER_LEFT,
        /** No traffic for the reliable channel's timeout. */
        LOST,
        /** Both peers simulated one frame to different state. */
        DESYNC
    }

    /** {@link redcon.duel.Side#NEAR} or {@link redcon.duel.Side#FAR}. */
    int localPlayer();

    /** True when this device owns rules ordering, clocks, and round results. */
    boolean isHost();

    /**
     * True when one runtime authors both seats.
     *
     * <p>Only the single-device development route does. The shell uses it to
     * decide whether the opposing fortress has to be stood up locally or is
     * going to arrive from the peer's own device.</p>
     */
    boolean standIn();

    /**
     * Shared deterministic seed for the current round's recovered simulation.
     * Network peers derive this from PAIR_OK; development runtimes use a fixed
     * value so their checks remain reproducible.
     */
    default long simulationSeed() { return 0x5deece66dL; }

    DuelSession session();

    /** Committed battle ticks since the runtime was created. */
    long timelineTick();

    /**
     * Move transport traffic on the game thread.
     *
     * <p>Called once per product frame before anything reads the session. The
     * in-process runtime has nothing to move; the UDP one drains its socket,
     * applies committed events, and retries a pending host decision.</p>
     */
    void update();

    Link link();

    void commitBuild(int player, FortDesign design);

    void setReady(int player, boolean ready);

    /** Advance a Build or Commencing frame which does not cross a deadline. */
    void advance(long ticks);

    /**
     * Admit and prepare one Battle simulation frame. Network lockstep may
     * return false while waiting for the peer; local development always steps.
     */
    default boolean beginBattleFrame() {
        advance(redcon.desktop.Duel.TICKS_PER_FRAME);
        return true;
    }

    /** Finish a successfully simulated frame before publishing its digest. */
    default void completeBattleFrame() { }

    /** A partial frame cannot be retried safely without a full state rollback. */
    default void abortBattleFrame(Throwable failure) {
        failure.printStackTrace();
        leave(localPlayer(), "simulation failed: " + failure);
    }

    /** Auto-commit this device's private draft at the shared Build deadline. */
    void expireBuild(int player, FortDesign design, long ticks);

    /**
     * Has the committed deadline for the current phase already passed?
     *
     * <p>Asked because a frame's tick count is the wrong way to detect it. A
     * networked runtime walks the session's remaining time down to match the
     * committed wall-clock deadline, so a device drawing slower than 60 Hz can
     * step from more than one frame remaining to past the deadline in a single
     * frame. After that the runtime holds the timeline and the remaining time
     * never falls, so any test of the form "is one frame at least the time
     * left" is false forever and the phase never ends.</p>
     *
     * <p>Local play has no committed deadline and answers false.</p>
     */
    default boolean phaseDeadlinePassed() {
        return false;
    }

    /** Report the locally simulated round outcome as terminal evidence. */
    void finishRound(DuelSession.RoundResult result);

    void order(int player, Order order);

    /** Attacker-authored shot launch from this device's own fortress. */
    void fire(int player, long shotId, int weaponId, int ordnanceType,
              float aimX, float aimY);

    /** Defender-authored damage resolved against this device's own fortress. */
    void impact(int player, long shotId, int roomId, float damage,
                DuelEvent.ImpactEffect... effects);

    /** Defender-authored drift correction for this device's own fortress. */
    void synchronizeFort(int player, int[] roomIds, float[] hitPoints,
                         int[] states);

    /**
     * True when this device owes the peer another correction for its fortress.
     *
     * <p>Corrections are periodic rather than per-frame: the fort roster is 33
     * rooms and the peer only needs the drift, not a stream. A runtime with no
     * peer has nothing to correct and always reports false.</p>
     */
    boolean correctionDue();

    /** Publish this device's digest of the just-completed lockstep frame. */
    default void checkpointDigest(int player, long digest) { }

    void setRematchReady(int player, boolean ready);

    void leave(int player, String reason);

    void attachBattleConsumer(DuelBattleEventConsumer consumer);

    void detachBattleConsumer(DuelBattleEventConsumer consumer);

    @Override
    void close();
}
