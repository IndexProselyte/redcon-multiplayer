package redcon.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import redcon.duel.Order;
import redcon.duel.Side;

/** Pure deterministic input scheduler and two-seat frame barrier. */
public final class DuelLockstep {
    public static final int MIN_DELAY = 2;
    public static final int MAX_DELAY = 8;
    public static final int DEFAULT_DELAY = 4;

    private final long matchId;
    private final int round;
    private final int localPlayer;
    private final int delay;
    @SuppressWarnings("unchecked")
    private final NavigableMap<Long, LockstepInputFrame>[] received =
        new NavigableMap[] {new TreeMap<>(), new TreeMap<>()};
    private final NavigableMap<Long, List<Order>> localDrafts = new TreeMap<>();
    private final NavigableMap<Long, List<LockstepInputFrame>> consumed = new TreeMap<>();
    private static final int DIAGNOSTIC_FRAMES = 360;
    private long nextFrame;
    private long remoteConfirmed = -1;

    public DuelLockstep(long matchId, int localPlayer, int delay) {
        this(matchId, 1, localPlayer, delay);
    }

    public DuelLockstep(long matchId, int round, int localPlayer, int delay) {
        DuelEvent.requirePlayer(localPlayer);
        if (matchId == 0 || round < 1 || delay < MIN_DELAY || delay > MAX_DELAY) {
            throw new IllegalArgumentException("invalid lockstep configuration");
        }
        this.matchId = matchId;
        this.round = round;
        this.localPlayer = localPlayer;
        this.delay = delay;
        // Neither player can have authored input before Battle appears.
        for (long frame = 0; frame < delay; frame++) {
            received[Side.NEAR].put(frame, empty(Side.NEAR, frame));
            received[Side.FAR].put(frame, empty(Side.FAR, frame));
        }
    }

    public long nextFrame() { return nextFrame; }
    public long confirmedFrame() { return nextFrame - 1; }
    public long remoteConfirmedFrame() { return remoteConfirmed; }
    public int delay() { return delay; }

    /** One full measured RTT plus two frames of jitter budget. */
    public static int delayForRoundTrip(long roundTripMillis) {
        if (roundTripMillis < 0) {
            throw new IllegalArgumentException("negative round trip");
        }
        long networkFrames = (roundTripMillis * 60L + 999L) / 1000L;
        return (int) Math.max(MIN_DELAY,
            Math.min(MAX_DELAY, networkFrames + 2L));
    }

    /** Compact recent declarations for a deterministic desync report. */
    public String diagnosticHistory() {
        StringBuilder out = new StringBuilder();
        long first = Math.max(0, nextFrame - DIAGNOSTIC_FRAMES);
        long last = nextFrame + delay;
        for (long frame = first; frame <= last; frame++) {
            out.append("frame ").append(frame);
            for (int player = Side.NEAR; player <= Side.FAR; player++) {
                LockstepInputFrame input = received[player].get(frame);
                List<LockstepInputFrame> past = consumed.get(frame);
                if (past != null) input = past.get(player);
                out.append(player == Side.NEAR ? " near=" : " far=");
                if (input == null) {
                    out.append('?');
                } else if (input.orders().isEmpty()) {
                    out.append('-');
                } else {
                    for (Order order : input.orders()) {
                        out.append(order.kind).append('(')
                            .append(order.a).append(',').append(order.b)
                            .append(") ");
                    }
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Schedule local input fairly; it never mutates the current frame. */
    public long schedule(Order order) {
        if (order == null) throw new IllegalArgumentException("order is null");
        // The render loop may have advertised the nominal target as empty
        // while waiting at the barrier. Never mutate a declaration already on
        // the wire; move the new command to the first unsealed future frame.
        long target = Math.max(nextFrame + delay,
            received[localPlayer].isEmpty()
                ? 0 : received[localPlayer].lastKey() + 1);
        // A group move or aim-all is many commands. Spill in issue order,
        // rather than throwing from input dispatch or making an oversized UDP packet.
        if (!localDrafts.isEmpty()) target = Math.max(target, localDrafts.lastKey());
        List<Order> draft = localDrafts.computeIfAbsent(target, ignored -> new ArrayList<>());
        if (draft.size() >= LockstepInputFrame.MAX_ORDERS) {
            target++;
            draft = localDrafts.computeIfAbsent(target, ignored -> new ArrayList<>());
        }
        draft.add(order.copy());
        return target;
    }

    /** Declare the future local frame and return the redundant send window. */
    public LockstepInputPacket outbound() {
        long newest = nextFrame + delay;
        // Retransmit from the peer's first unacknowledged frame. A window
        // anchored only at newest forgets a missing frame permanently when
        // delay == HISTORY, leaving a live connection stuck at the barrier.
        long oldest = Math.max(0, remoteConfirmed + 1);
        for (long frame = Math.max(nextFrame, oldest); frame <= newest; frame++) sealLocal(frame);
        long last = Math.min(newest, oldest + LockstepInputPacket.HISTORY - 1);
        List<LockstepInputFrame> history = new ArrayList<>();
        for (long frame = oldest; frame <= last; frame++) {
            sealLocal(frame);
            history.add(received[localPlayer].get(frame));
        }
        return new LockstepInputPacket(
            matchId, round, localPlayer, confirmedFrame(), history);
    }

    public void accept(LockstepInputPacket packet) {
        if (packet == null || packet.matchId != matchId
                || packet.player == localPlayer) {
            throw new IllegalArgumentException("foreign lockstep packet");
        }
        if (packet.round != round) return; // delayed traffic from a prior round
        remoteConfirmed = Math.max(remoteConfirmed, packet.confirmedFrame);
        for (LockstepInputFrame frame : packet.frames()) {
            if (frame.frame >= nextFrame) {
                LockstepInputFrame prior = received[frame.player].putIfAbsent(
                    frame.frame, frame);
                if (prior != null && !same(prior, frame)) {
                    throw new IllegalStateException(
                        "peer changed an already-declared input frame");
                }
            }
        }
    }

    public boolean ready() {
        return received[Side.NEAR].containsKey(nextFrame)
            && received[Side.FAR].containsKey(nextFrame);
    }

    /** Inspect the admitted inputs without acknowledging an unfinished step. */
    public List<LockstepInputFrame> peek() {
        if (!ready()) throw new IllegalStateException("lockstep frame is not ready");
        List<LockstepInputFrame> result = new ArrayList<>(2);
        result.add(received[Side.NEAR].get(nextFrame));
        result.add(received[Side.FAR].get(nextFrame));
        return Collections.unmodifiableList(result);
    }

    /** Acknowledge only after input application and engine stepping succeed. */
    public void complete() {
        List<LockstepInputFrame> result = peek();
        consumed.put(nextFrame, Collections.unmodifiableList(new ArrayList<>(result)));
        localDrafts.remove(nextFrame);
        nextFrame++;
        consumed.headMap(nextFrame - DIAGNOSTIC_FRAMES, false).clear();
        long discardBefore = Math.min(nextFrame - LockstepInputPacket.HISTORY,
                                      remoteConfirmed + 1);
        received[localPlayer].headMap(discardBefore, false).clear();
        received[localPlayer ^ 1].headMap(nextFrame, false).clear();
    }

    /** Convenience for transport-only consumers with no engine step. */
    public List<LockstepInputFrame> take() {
        List<LockstepInputFrame> result = peek();
        complete();
        return result;
    }

    private void sealLocal(long frame) {
        if (received[localPlayer].containsKey(frame)) return;
        List<Order> orders = localDrafts.get(frame);
        received[localPlayer].put(frame, new LockstepInputFrame(
            localPlayer, frame,
            orders == null ? Collections.emptyList() : orders));
    }

    private static LockstepInputFrame empty(int player, long frame) {
        return new LockstepInputFrame(player, frame, Collections.emptyList());
    }

    private static boolean same(LockstepInputFrame a, LockstepInputFrame b) {
        if (a.player != b.player || a.frame != b.frame) return false;
        List<Order> left = a.orders();
        List<Order> right = b.orders();
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Order x = left.get(i);
            Order y = right.get(i);
            if (x.kind != y.kind || x.a != y.a || x.b != y.b) return false;
        }
        return true;
    }
}
