package redcon.net;

import redcon.duel.Side;

/**
 * One event in the ordered history shared by both players.
 *
 * <p>The sequence is assigned by the channel, never by native input. UDP's
 * reliable-ordered layer will eventually reconstruct this exact type before
 * handing it to {@link DuelEventConsumer}.</p>
 */
public final class CommittedDuelEvent {

    private final long sequence;
    private final int sourcePlayer;
    private final DuelEvent event;

    public CommittedDuelEvent(long sequence, int sourcePlayer, DuelEvent event) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        requirePlayer(sourcePlayer);
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        this.sequence = sequence;
        this.sourcePlayer = sourcePlayer;
        this.event = event.copy();
    }

    public long sequence() {
        return sequence;
    }

    public int sourcePlayer() {
        return sourcePlayer;
    }

    /** A defensive payload copy, as if it had crossed a real transport. */
    public DuelEvent event() {
        return event.copy();
    }

    /** Give one consumer an envelope it cannot share with another consumer. */
    public CommittedDuelEvent copyForDelivery() {
        return new CommittedDuelEvent(sequence, sourcePlayer, event);
    }

    private static void requirePlayer(int player) {
        if (player != Side.NEAR && player != Side.FAR) {
            throw new IllegalArgumentException("bad player " + player);
        }
    }
}
