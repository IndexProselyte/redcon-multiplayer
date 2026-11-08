package redcon.net;

/** Serializes the host's committed history onto the reliable UDP channel. */
public final class UdpCommittedEventSender implements DuelEventConsumer {

    private final long matchId;
    private final UdpPeerTransport transport;

    public UdpCommittedEventSender(long matchId, UdpPeerTransport transport) {
        if (matchId == 0 || transport == null) {
            throw new IllegalArgumentException("match id and transport are required");
        }
        this.matchId = matchId;
        this.transport = transport;
    }

    @Override
    public void accept(CommittedDuelEvent event) {
        transport.send(DuelPacketCodec.encode(matchId, event));
    }
}
