package redcon.net;

/** Sends client-authored event requests to the host over reliable UDP. */
public final class UdpDuelRequestSink implements DuelEventSink {

    private final long matchId;
    private final UdpPeerTransport transport;

    public UdpDuelRequestSink(long matchId, UdpPeerTransport transport) {
        if (matchId == 0 || transport == null) {
            throw new IllegalArgumentException("match id and transport are required");
        }
        this.matchId = matchId;
        this.transport = transport;
    }

    @Override
    public void publish(int sourcePlayer, DuelEvent event) {
        transport.send(DuelPacketCodec.encodeRequest(matchId, sourcePlayer, event));
    }
}
