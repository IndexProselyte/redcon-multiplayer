package redcon.net;

/** The outbound half of the Phase 04 event boundary. */
public interface DuelEventSink {

    /** Submit an authored event for ordering and delivery to both peers. */
    void publish(int sourcePlayer, DuelEvent event);
}
