package redcon.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import redcon.duel.Side;

/**
 * Phase 04's first transport: synchronous, in-process, and deliberately small.
 *
 * <p>It still behaves like a transport where it matters. It assigns a single
 * monotonic sequence, broadcasts every committed event to both clients, and
 * gives every consumer a defensive payload copy. No consumer can accidentally
 * pass an engine object or mutable array to its peer.</p>
 */
public final class InProcessEventChannel implements DuelEventSink {

    private final List<DuelEventConsumer> consumers = new ArrayList<>();
    private final List<CommittedDuelEvent> history = new ArrayList<>();
    private long nextSequence = 1;

    /** Attach a client before the first event is published. */
    public synchronized void connect(DuelEventConsumer consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer is null");
        }
        if (nextSequence != 1) {
            throw new IllegalStateException("cannot join an event channel in progress");
        }
        if (consumers.contains(consumer)) {
            throw new IllegalArgumentException("consumer is already connected");
        }
        consumers.add(consumer);
    }

    @Override
    public synchronized void publish(int sourcePlayer, DuelEvent event) {
        requirePlayer(sourcePlayer);
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        if (consumers.size() != 2) {
            throw new IllegalStateException("a duel channel requires exactly two clients");
        }
        CommittedDuelEvent committed =
            new CommittedDuelEvent(nextSequence++, sourcePlayer, event);
        history.add(committed.copyForDelivery());
        for (DuelEventConsumer consumer : consumers) {
            consumer.accept(committed.copyForDelivery());
        }
    }

    public synchronized long committedCount() {
        return nextSequence - 1;
    }

    /** A defensive replayable copy of the complete committed history. */
    public synchronized List<CommittedDuelEvent> history() {
        List<CommittedDuelEvent> result = new ArrayList<>();
        for (CommittedDuelEvent event : history) {
            result.add(event.copyForDelivery());
        }
        return Collections.unmodifiableList(result);
    }

    private static void requirePlayer(int player) {
        if (player != Side.NEAR && player != Side.FAR) {
            throw new IllegalArgumentException("bad player " + player);
        }
    }
}
