package redcon.net;

/** One peer's deterministic state digest at a completed frame. */
public final class LockstepDigestPacket {
    public final long matchId;
    public final int round;
    public final int player;
    public final long frame;
    public final long digest;

    public LockstepDigestPacket(long matchId, int player,
                                long frame, long digest) {
        this(matchId, 1, player, frame, digest);
    }

    public LockstepDigestPacket(long matchId, int round, int player,
                                long frame, long digest) {
        DuelEvent.requirePlayer(player);
        if (matchId == 0 || round < 1 || frame < 0) {
            throw new IllegalArgumentException("invalid lockstep digest");
        }
        this.matchId = matchId;
        this.round = round;
        this.player = player;
        this.frame = frame;
        this.digest = digest;
    }
}
