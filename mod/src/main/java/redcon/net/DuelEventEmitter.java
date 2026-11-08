package redcon.net;

import redcon.duel.FortDesign;
import redcon.duel.DuelSession;
import redcon.duel.Order;

/**
 * Outbound events authored by one native client.
 *
 * <p>UI adapters call this class instead of mutating peer state directly. The
 * event returns to the local client through the same consumer path used by the
 * remote client, so local/in-process success cannot hide a missing network
 * application path.</p>
 */
public final class DuelEventEmitter {

    private final int localPlayer;
    private final DuelEventSink sink;

    public DuelEventEmitter(int localPlayer, DuelEventSink sink) {
        DuelEvent.requirePlayer(localPlayer);
        if (sink == null) {
            throw new IllegalArgumentException("event sink is null");
        }
        this.localPlayer = localPlayer;
        this.sink = sink;
    }

    public void proposeRules(int creditBudget, int buildSeconds, int matchSeconds) {
        emit(new DuelEvent.RulesPropose(localPlayer, creditBudget,
                                        buildSeconds, matchSeconds));
    }

    public void commitBuild(FortDesign design) {
        emit(new DuelEvent.BuildCommit(localPlayer, design));
    }

    public void setReady(boolean ready) {
        emit(new DuelEvent.ReadySet(localPlayer, ready));
    }

    public void setClock(int round, DuelEvent.ClockPhase phase,
                         long startTick, long deadlineTick) {
        emit(new DuelEvent.PhaseClock(round, phase, startTick, deadlineTick));
    }

    public void setClock(int round, DuelEvent.ClockPhase phase,
                         long startTick, long deadlineTick, int inputDelay,
                         long hostDeadlineMillis) {
        emit(new DuelEvent.PhaseClock(round, phase, startTick, deadlineTick,
                                      inputDelay, hostDeadlineMillis));
    }

    /** The semantic outbound event produced by a native battle gesture. */
    public void order(long tick, Order order) {
        emit(new DuelEvent.OrderIssued(localPlayer, tick, order));
    }

    /** Report a shot launched by this client's authoritative fortress. */
    public void fire(long tick, long shotId, int weaponId, int ordnanceType,
                     float aimX, float aimY) {
        emit(new DuelEvent.Fire(localPlayer, tick, shotId, weaponId,
                                ordnanceType, aimX, aimY));
    }

    /** Report damage resolved against this client's authoritative fortress. */
    public void impact(long tick, long shotId, int roomId, float damage,
                       DuelEvent.ImpactEffect... effects) {
        emit(new DuelEvent.Impact(localPlayer, tick, shotId, roomId,
                                  damage, effects));
    }

    public void synchronizeFort(long tick, int[] roomIds,
                                float[] hitPoints, int[] states) {
        emit(new DuelEvent.FortSync(localPlayer, tick, roomIds, hitPoints, states));
    }

    public void defeated(long tick) {
        emit(new DuelEvent.Defeated(localPlayer, tick));
    }

    /** Host-authored final result after all terminal evidence is resolved. */
    public void roundResult(DuelSession.RoundResult result) {
        if (result == null) {
            throw new IllegalArgumentException("round result is null");
        }
        emit(new DuelEvent.RoundResult(
            result.winner, result.ending, result.seconds));
    }

    public void setRematchReady(boolean ready) {
        emit(new DuelEvent.RematchReady(localPlayer, ready));
    }

    public void leave(String reason) {
        emit(new DuelEvent.Leave(localPlayer, reason));
    }

    private void emit(DuelEvent event) {
        sink.publish(localPlayer, event);
    }
}
