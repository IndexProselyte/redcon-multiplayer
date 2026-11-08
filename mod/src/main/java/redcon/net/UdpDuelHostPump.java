package redcon.net;

import java.io.IOException;

/** Receives guest requests and submits valid ones to the host coordinator. */
public final class UdpDuelHostPump {

    private final long matchId;
    private final UdpPeerTransport transport;
    private final DuelSessionCoordinator coordinator;
    private int rejectedRequests;
    private long reportedRoundTripMillis = -1;

    public UdpDuelHostPump(long matchId, UdpPeerTransport transport,
                           DuelSessionCoordinator coordinator) {
        if (matchId == 0 || transport == null || coordinator == null) {
            throw new IllegalArgumentException(
                "match id, transport, and coordinator are required");
        }
        this.matchId = matchId;
        this.transport = transport;
        this.coordinator = coordinator;
    }

    public void update(long nowMillis) throws IOException {
        transport.update(nowMillis);
        for (byte[] message : transport.drainDelivered()) {
            DuelPacketCodec.DecodedRequest request;
            try {
                request = DuelPacketCodec.decodeRequest(message);
            } catch (IllegalArgumentException notRequest) {
                handleClock(message, nowMillis);
                continue;
            }
            try {
                if (request.matchId != matchId) {
                    throw new IllegalArgumentException("request match mismatch");
                }
                coordinator.publish(request.sourcePlayer, request.event);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                rejectedRequests++;
            }
        }
    }

    private void handleClock(byte[] message, long nowMillis) {
        try {
            Object clock = DuelClockSync.decode(message);
            if (!(clock instanceof DuelClockSync.Probe)) {
                throw new IllegalArgumentException("host expected a clock probe");
            }
            DuelClockSync.Probe probe = (DuelClockSync.Probe) clock;
            if (probe.reportedRoundTripMillis >= 0) {
                reportedRoundTripMillis = probe.reportedRoundTripMillis;
            }
            transport.send(DuelClockSync.encode(
                DuelClockSync.answer(probe, nowMillis, nowMillis)));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            rejectedRequests++;
        }
    }

    public int rejectedRequests() { return rejectedRequests; }

    /**
     * The guest's most recent reported round trip, or -1 before it has
     * measured one. The host reads this once per round to choose the
     * committed input delay.
     */
    public long reportedRoundTripMillis() { return reportedRoundTripMillis; }
}
