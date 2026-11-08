package redcon.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Redundant unreliable input packet for one peer. */
public final class LockstepInputPacket {
    public static final int HISTORY = 8;

    public final long matchId;
    public final int round;
    public final int player;
    public final long confirmedFrame;
    private final List<LockstepInputFrame> frames;

    public LockstepInputPacket(long matchId, int player, long confirmedFrame,
                               List<LockstepInputFrame> frames) {
        this(matchId, 1, player, confirmedFrame, frames);
    }

    public LockstepInputPacket(long matchId, int round, int player, long confirmedFrame,
                               List<LockstepInputFrame> frames) {
        DuelEvent.requirePlayer(player);
        if (matchId == 0 || round < 1 || confirmedFrame < -1 || frames == null
                || frames.isEmpty() || frames.size() > HISTORY) {
            throw new IllegalArgumentException("invalid lockstep packet");
        }
        ArrayList<LockstepInputFrame> copy = new ArrayList<>(frames.size());
        long previous = -1;
        for (LockstepInputFrame frame : frames) {
            if (frame == null || frame.player != player || frame.frame <= previous) {
                throw new IllegalArgumentException(
                    "lockstep packet frames must be ordered and owned by sender");
            }
            copy.add(new LockstepInputFrame(player, frame.frame, frame.orders()));
            previous = frame.frame;
        }
        this.matchId = matchId;
        this.round = round;
        this.player = player;
        this.confirmedFrame = confirmedFrame;
        this.frames = Collections.unmodifiableList(copy);
    }

    public List<LockstepInputFrame> frames() {
        return new ArrayList<>(frames);
    }
}
