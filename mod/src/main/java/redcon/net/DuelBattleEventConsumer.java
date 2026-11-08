package redcon.net;

/** Native battle seam used by the transport-independent session consumer. */
public interface DuelBattleEventConsumer {

    void applyOrder(DuelEvent.OrderIssued order);

    void applyFire(DuelEvent.Fire fire);

    void applyImpact(DuelEvent.Impact impact);

    void applyFortSync(DuelEvent.FortSync correction);

    /**
     * This device's simulation state, in enough detail to diff against a peer.
     *
     * <p>A digest mismatch names the frame and nothing else: two hashes that
     * differ say the two devices disagree, never about what. Both peers write
     * a report at the same committed frame, so whatever each puts here can be
     * compared line for line - which room, which soldier, which field, and by
     * how much.</p>
     *
     * <p>Empty by default: the transport does not require anyone to be able to
     * describe themselves, and the harnesses that implement this seam without
     * a battle screen have nothing to say.</p>
     */
    default String stateReport() {
        return "";
    }
}
