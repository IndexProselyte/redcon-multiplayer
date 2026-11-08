package redcon.net;

/**
 * Applies one event after a channel has committed it to the match history.
 *
 * <p>This is the Phase 04 transport boundary. An in-process relay, a UDP
 * reliable-ordered channel, and a replay file all deliver the same envelope
 * here; game/session code must not know which one was responsible.</p>
 */
public interface DuelEventConsumer {

    void accept(CommittedDuelEvent event);
}
