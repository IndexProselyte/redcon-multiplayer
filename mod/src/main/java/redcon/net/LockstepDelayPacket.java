package redcon.net;

/** Guest measurement used by the host to commit the next round's delay. */
public final class LockstepDelayPacket {
    public final long matchId;
    public final int player;
    public final long roundTripMillis;

    public LockstepDelayPacket(long matchId, int player, long roundTripMillis) {
        DuelEvent.requirePlayer(player);
        if (matchId == 0 || roundTripMillis < 0 || roundTripMillis > 60_000) {
            throw new IllegalArgumentException("invalid delay measurement");
        }
        this.matchId = matchId;
        this.player = player;
        this.roundTripMillis = roundTripMillis;
    }
}
