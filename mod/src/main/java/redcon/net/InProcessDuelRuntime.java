package redcon.net;

import java.util.List;
import redcon.desktop.Duel;
import redcon.duel.DuelMatch;
import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.duel.Order;
import redcon.duel.Side;

/**
 * Product lifecycle wired through the first, in-process event transport.
 *
 * <p>This is intentionally transport-specific only at construction. Product
 * actions use the same emitters and product-session consumer that UDP uses;
 * replacing the sink/pumps later does not create another gameplay path.</p>
 */
public final class InProcessDuelRuntime implements DuelRuntime {

    private final int localPlayer;
    private final int hostPlayer;
    private final DuelSession session;
    private final InProcessEventChannel channel = new InProcessEventChannel();
    private final DuelSessionCoordinator coordinator;
    private final DuelEventEmitter[] emitters = new DuelEventEmitter[2];
    private final DuelSessionEventConsumer productConsumer;
    private final DuelPeerState remoteState;
    private final DuelBattleEventRouter battleRouter =
        new DuelBattleEventRouter();
    private long timelineTick;

    public InProcessDuelRuntime(int localPlayer, int hostPlayer,
                                DuelSession session) {
        DuelEvent.requirePlayer(localPlayer);
        DuelEvent.requirePlayer(hostPlayer);
        if (session == null || session.round() != 1
                || session.phase() != DuelSession.Phase.BUILD) {
            throw new IllegalArgumentException(
                "a new Build session is required");
        }
        this.localPlayer = localPlayer;
        this.hostPlayer = hostPlayer;
        this.session = session;
        productConsumer = new DuelSessionEventConsumer(
            localPlayer, session, battleRouter);
        remoteState = new DuelPeerState(localPlayer ^ 1);
        channel.connect(productConsumer);
        channel.connect(remoteState);
        coordinator = new DuelSessionCoordinator(hostPlayer, channel);
        emitters[Side.NEAR] = new DuelEventEmitter(Side.NEAR, coordinator);
        emitters[Side.FAR] = new DuelEventEmitter(Side.FAR, coordinator);

        emitter(hostPlayer).proposeRules(
            session.creditBudget(), session.buildSeconds(),
            session.matchSeconds());
        emitter(hostPlayer).setClock(
            session.round(), DuelEvent.ClockPhase.BUILD, 0,
            (long) session.buildSeconds() * Duel.TICKS_PER_SECOND);
    }

    @Override public int localPlayer() { return localPlayer; }

    /**
     * True whenever this runtime holds both seats.
     *
     * <p>The single-process stand-in authors every event on the channel, so it
     * is by definition the host of the match it is carrying.</p>
     */
    @Override public boolean isHost() { return true; }

    @Override public boolean standIn() { return true; }

    /** Nothing to move: the channel delivers synchronously inside publish. */
    @Override public void update() { }

    /** An in-process peer cannot be unplugged. */
    @Override public Link link() {
        return session.phase() == DuelSession.Phase.CLOSED
            ? Link.PEER_LEFT : Link.LIVE;
    }

    @Override
    public void commitBuild(int player, FortDesign design) {
        emitter(player).commitBuild(design);
    }

    @Override
    public void setReady(int player, boolean ready) {
        emitter(player).setReady(ready);
        if (session.phase() == DuelSession.Phase.COMMENCING
                && coordinator.phase()
                    == DuelSessionCoordinator.Phase.BUILD) {
            emitter(hostPlayer).setClock(
                session.round(), DuelEvent.ClockPhase.COMMENCING,
                timelineTick,
                timelineTick + (long) DuelSession.COMMENCING_SECONDS
                    * Duel.TICKS_PER_SECOND);
        }
    }

    /** Advance a Build frame which does not cross its committed deadline. */
    @Override
    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        if (session.phase() == DuelSession.Phase.BUILD) {
            if (ticks >= session.buildTicksRemaining()) {
                throw new IllegalStateException(
                    "Build deadline requires an authored fort commit");
            }
            session.advance(ticks);
            timelineTick += ticks;
            return;
        }
        if (session.phase() == DuelSession.Phase.COMMENCING) {
            long remaining = session.commencingTicksRemaining();
            if (ticks < remaining) {
                session.advance(ticks);
                timelineTick += ticks;
                return;
            }
            timelineTick += remaining;
            emitter(hostPlayer).setClock(
                session.round(), DuelEvent.ClockPhase.BATTLE,
                timelineTick,
                timelineTick + (long) session.matchSeconds()
                    * Duel.TICKS_PER_SECOND);
            timelineTick += ticks - remaining;
            return;
        }
        timelineTick += ticks;
    }

    /** Auto-commit this device's private draft exactly at the Build deadline. */
    @Override
    public void expireBuild(int player, FortDesign design, long ticks) {
        if (session.phase() != DuelSession.Phase.BUILD
                || ticks < session.buildTicksRemaining()) {
            throw new IllegalArgumentException(
                "Build expiry must cross the current deadline");
        }
        long remaining = session.buildTicksRemaining();
        timelineTick += remaining;
        commitBuild(player, design);
        setReady(player, true);
        if (session.phase() != DuelSession.Phase.COMMENCING) {
            throw new IllegalStateException(
                "the peer did not commit before the shared Build deadline");
        }
        long overflow = ticks - remaining;
        if (overflow > 0) {
            advance(overflow);
        }
    }

    /** Commit the exact native match result through the shared history. */
    @Override
    public void finishRound(DuelSession.RoundResult result) {
        if (result == null || session.phase() != DuelSession.Phase.BATTLE) {
            throw new IllegalArgumentException("live round result is required");
        }
        if (result.ending == DuelMatch.Ending.DEFEAT) {
            emitter(result.winner ^ 1).defeated(timelineTick);
        } else if (result.ending == DuelMatch.Ending.SIMULTANEOUS) {
            emitter(Side.NEAR).defeated(timelineTick);
            emitter(Side.FAR).defeated(timelineTick);
        }
        emitter(hostPlayer).roundResult(result);
    }

    @Override
    public void order(int player, Order order) {
        emitter(player).order(timelineTick, order);
    }

    @Override
    public void fire(int player, long shotId, int weaponId, int ordnanceType,
                     float aimX, float aimY) {
        emitter(player).fire(timelineTick, shotId, weaponId, ordnanceType,
                             aimX, aimY);
    }

    @Override
    public void impact(int player, long shotId, int roomId, float damage,
                       DuelEvent.ImpactEffect... effects) {
        emitter(player).impact(timelineTick, shotId, roomId, damage, effects);
    }

    @Override
    public void synchronizeFort(int player, int[] roomIds, float[] hitPoints,
                                int[] states) {
        emitter(player).synchronizeFort(timelineTick, roomIds, hitPoints, states);
    }

    /** There is no second device to correct: this runtime is both of them. */
    @Override public boolean correctionDue() { return false; }

    @Override
    public void setRematchReady(int player, boolean ready) {
        emitter(player).setRematchReady(ready);
        if (session.phase() == DuelSession.Phase.BUILD
                && coordinator.phase()
                    == DuelSessionCoordinator.Phase.RESULT) {
            emitter(hostPlayer).setClock(
                session.round(), DuelEvent.ClockPhase.BUILD,
                timelineTick,
                timelineTick + (long) session.buildSeconds()
                    * Duel.TICKS_PER_SECOND);
        }
    }

    @Override
    public void leave(int player, String reason) {
        if (session.phase() != DuelSession.Phase.CLOSED) {
            emitter(player).leave(reason);
        }
    }

    @Override
    public void attachBattleConsumer(DuelBattleEventConsumer consumer) {
        battleRouter.attach(consumer);
    }

    @Override
    public void detachBattleConsumer(DuelBattleEventConsumer consumer) {
        battleRouter.detach(consumer);
    }

    @Override public DuelSession session() { return session; }
    @Override public long timelineTick() { return timelineTick; }
    public long committedCount() { return channel.committedCount(); }
    public long appliedCount() { return productConsumer.lastSequence(); }
    public long remoteAppliedCount() { return remoteState.lastSequence(); }
    public DuelEvent.Defeated remoteDefeated() {
        return remoteState.defeated();
    }
    public DuelEvent.RoundResult remoteRoundResult() {
        return remoteState.roundResult();
    }
    public List<CommittedDuelEvent> history() { return channel.history(); }

    /** The channel owns no socket, thread, or file; closing releases nothing. */
    @Override public void close() { }

    private DuelEventEmitter emitter(int player) {
        DuelEvent.requirePlayer(player);
        return emitters[player];
    }
}
