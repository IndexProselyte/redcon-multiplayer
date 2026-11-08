package redcon.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import redcon.duel.FortDesign;
import redcon.duel.Side;

/**
 * Transport-independent application of the committed duel history.
 *
 * <p>Both clients own one of these. Every local action comes back through
 * {@link #accept} beside remote actions, and both reducers therefore converge
 * on the same shared match facts. The live REDCON adapters added next consume
 * this state and its battle-event stream.</p>
 */
public final class DuelPeerState implements DuelEventConsumer {

    private final int localPlayer;
    private final FortDesign[] committed = new FortDesign[2];
    private final boolean[] ready = new boolean[2];
    private final boolean[] rematchReady = new boolean[2];
    private final DuelEvent.FortSync[] corrections = new DuelEvent.FortSync[2];
    private final EnumMap<DuelEvent.ClockPhase, DuelEvent.PhaseClock> clocks =
        new EnumMap<>(DuelEvent.ClockPhase.class);
    private final List<DuelEvent.OrderIssued> orders = new ArrayList<>();
    private final List<DuelEvent.Fire> fires = new ArrayList<>();
    private final List<DuelEvent.Impact> impacts = new ArrayList<>();

    private DuelEvent.RulesSet rules;
    private DuelEvent.Defeated defeated;
    private DuelEvent.RoundResult roundResult;
    private DuelEvent.Leave leave;
    private long lastSequence;
    private int localEvents;
    private int remoteEvents;

    public DuelPeerState(int localPlayer) {
        DuelEvent.requirePlayer(localPlayer);
        this.localPlayer = localPlayer;
    }

    @Override
    public void accept(CommittedDuelEvent committedEvent) {
        if (committedEvent == null) {
            throw new IllegalArgumentException("committed event is null");
        }
        long sequence = committedEvent.sequence();
        if (sequence <= lastSequence) {
            return;
        }
        if (sequence != lastSequence + 1) {
            throw new IllegalStateException("event gap: expected "
                + (lastSequence + 1) + ", received " + sequence);
        }

        DuelEvent event = committedEvent.event();
        apply(event);
        lastSequence = sequence;
        if (committedEvent.sourcePlayer() == localPlayer) {
            localEvents++;
        } else {
            remoteEvents++;
        }
    }

    private void apply(DuelEvent event) {
        switch (event.kind()) {
            case RULES_PROPOSE:
                throw new IllegalArgumentException(
                    "rule proposals must be resolved before commit");
            case RULES_SET: {
                DuelEvent.RulesSet incoming = (DuelEvent.RulesSet) event;
                if (rules == null || incoming.revision > rules.revision) {
                    rules = (DuelEvent.RulesSet) incoming.copy();
                }
                break;
            }
            case BUILD_COMMIT: {
                DuelEvent.BuildCommit build = (DuelEvent.BuildCommit) event;
                committed[build.player] = build.design();
                break;
            }
            case READY_SET: {
                DuelEvent.ReadySet set = (DuelEvent.ReadySet) event;
                ready[set.player] = set.ready;
                break;
            }
            case PHASE_CLOCK: {
                DuelEvent.PhaseClock clock = (DuelEvent.PhaseClock) event;
                DuelEvent.PhaseClock current = clocks.get(clock.phase);
                if (clock.phase == DuelEvent.ClockPhase.BUILD
                        && (current == null || clock.round > current.round)) {
                    clearRoundState();
                }
                if (current == null || clock.round >= current.round) {
                    clocks.put(clock.phase, (DuelEvent.PhaseClock) clock.copy());
                }
                break;
            }
            case ORDER:
                orders.add((DuelEvent.OrderIssued) event.copy());
                break;
            case FIRE:
                fires.add((DuelEvent.Fire) event.copy());
                break;
            case IMPACT:
                impacts.add((DuelEvent.Impact) event.copy());
                break;
            case FORT_SYNC: {
                DuelEvent.FortSync sync = (DuelEvent.FortSync) event;
                DuelEvent.FortSync current = corrections[sync.player];
                if (current == null || sync.tick >= current.tick) {
                    corrections[sync.player] = (DuelEvent.FortSync) sync.copy();
                }
                break;
            }
            case DEFEATED:
                defeated = (DuelEvent.Defeated) event.copy();
                break;
            case ROUND_RESULT:
                roundResult = (DuelEvent.RoundResult) event.copy();
                break;
            case REMATCH_READY: {
                DuelEvent.RematchReady set = (DuelEvent.RematchReady) event;
                rematchReady[set.player] = set.ready;
                break;
            }
            case LEAVE:
                leave = (DuelEvent.Leave) event.copy();
                break;
            default:
                throw new IllegalArgumentException("unsupported event " + event.kind());
        }
    }

    private void clearRoundState() {
        committed[Side.NEAR] = null;
        committed[Side.FAR] = null;
        ready[Side.NEAR] = false;
        ready[Side.FAR] = false;
        rematchReady[Side.NEAR] = false;
        rematchReady[Side.FAR] = false;
        corrections[Side.NEAR] = null;
        corrections[Side.FAR] = null;
        defeated = null;
        roundResult = null;
    }

    /**
     * Adopt a history whose earlier events were reduced somewhere else.
     *
     * <p>Used only when a reducer is created part way through a committed
     * stream - a networked session is constructed from the rules revision that
     * was settled before it existed. It refuses to move backwards, so it can
     * never be used to hide a real gap in a running reducer.</p>
     */
    void skipTo(long sequence) {
        if (sequence < lastSequence) {
            throw new IllegalArgumentException(
                "a reducer cannot rewind to sequence " + sequence);
        }
        lastSequence = sequence;
    }

    public int localPlayer() { return localPlayer; }
    public long lastSequence() { return lastSequence; }
    public int localEventCount() { return localEvents; }
    public int remoteEventCount() { return remoteEvents; }

    public DuelEvent.RulesSet rules() {
        return rules == null ? null : (DuelEvent.RulesSet) rules.copy();
    }

    public FortDesign committedFort(int player) {
        requirePlayer(player);
        return committed[player] == null ? null : committed[player].copy();
    }

    public boolean ready(int player) {
        requirePlayer(player);
        return ready[player];
    }

    public boolean rematchReady(int player) {
        requirePlayer(player);
        return rematchReady[player];
    }

    public DuelEvent.PhaseClock clock(DuelEvent.ClockPhase phase) {
        DuelEvent.PhaseClock clock = clocks.get(phase);
        return clock == null ? null : (DuelEvent.PhaseClock) clock.copy();
    }

    public List<DuelEvent.OrderIssued> orders() {
        return immutableOrderCopies(orders);
    }

    public List<DuelEvent.Fire> fires() {
        List<DuelEvent.Fire> result = new ArrayList<>();
        for (DuelEvent.Fire fire : fires) {
            result.add((DuelEvent.Fire) fire.copy());
        }
        return Collections.unmodifiableList(result);
    }

    public List<DuelEvent.Impact> impacts() {
        List<DuelEvent.Impact> result = new ArrayList<>();
        for (DuelEvent.Impact impact : impacts) {
            result.add((DuelEvent.Impact) impact.copy());
        }
        return Collections.unmodifiableList(result);
    }

    public DuelEvent.FortSync correction(int player) {
        requirePlayer(player);
        DuelEvent.FortSync sync = corrections[player];
        return sync == null ? null : (DuelEvent.FortSync) sync.copy();
    }

    public DuelEvent.Defeated defeated() {
        return defeated == null ? null : (DuelEvent.Defeated) defeated.copy();
    }

    public DuelEvent.RoundResult roundResult() {
        return roundResult == null
            ? null : (DuelEvent.RoundResult) roundResult.copy();
    }

    public DuelEvent.Leave leaveEvent() {
        return leave == null ? null : (DuelEvent.Leave) leave.copy();
    }

    private static List<DuelEvent.OrderIssued> immutableOrderCopies(
            List<DuelEvent.OrderIssued> source) {
        List<DuelEvent.OrderIssued> result = new ArrayList<>();
        for (DuelEvent.OrderIssued order : source) {
            result.add((DuelEvent.OrderIssued) order.copy());
        }
        return Collections.unmodifiableList(result);
    }

    private static void requirePlayer(int player) {
        if (player != Side.NEAR && player != Side.FAR) {
            throw new IllegalArgumentException("bad player " + player);
        }
    }
}
