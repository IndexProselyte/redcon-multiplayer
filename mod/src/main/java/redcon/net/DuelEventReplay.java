package redcon.net;

import java.util.List;

/** Replays a recorded committed history through the normal consumer boundary. */
public final class DuelEventReplay {

    public static void apply(List<CommittedDuelEvent> history,
                             DuelEventConsumer consumer) {
        if (history == null || consumer == null) {
            throw new IllegalArgumentException("history and consumer are required");
        }
        long expected = 1;
        for (CommittedDuelEvent event : history) {
            if (event == null || event.sequence() != expected) {
                throw new IllegalArgumentException(
                    "invalid replay sequence at " + expected);
            }
            consumer.accept(event.copyForDelivery());
            expected++;
        }
    }

    private DuelEventReplay() {
    }
}
