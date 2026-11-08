package redcon.net;

import java.io.IOException;

/** Decodes the host's committed history into the guest event consumer. */
public final class UdpDuelClientPump {

    public static final long CLOCK_SAMPLE_MILLIS = 1000;

    private final long matchId;
    private final UdpPeerTransport transport;
    private final DuelEventConsumer consumer;
    private final DuelClockSync.Estimator clock;
    private int rejectedEvents;
    private long lastClockSampleMillis = Long.MIN_VALUE;

    public UdpDuelClientPump(long matchId, UdpPeerTransport transport,
                             DuelEventConsumer consumer) {
        this(matchId, transport, consumer, null);
    }

    public UdpDuelClientPump(long matchId, UdpPeerTransport transport,
                             DuelEventConsumer consumer,
                             DuelClockSync.Estimator clock) {
        if (matchId == 0 || transport == null || consumer == null) {
            throw new IllegalArgumentException(
                "match id, transport, and consumer are required");
        }
        this.matchId = matchId;
        this.transport = transport;
        this.consumer = consumer;
        this.clock = clock;
    }

    public void update(long nowMillis) throws IOException {
        sampleClock(nowMillis);
        transport.update(nowMillis);
        for (byte[] message : transport.drainDelivered()) {
            DuelPacketCodec.Decoded decoded;
            try {
                decoded = DuelPacketCodec.decode(message);
            } catch (IllegalArgumentException notEvent) {
                handleClock(message, nowMillis);
                continue;
            }
            try {
                if (decoded.matchId != matchId) {
                    throw new IllegalArgumentException("event match mismatch");
                }
                consumer.accept(decoded.committed);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                rejectedEvents++;
                System.err.println("duel client rejected #"
                    + decoded.committed.sequence() + " "
                    + decoded.committed.event().kind() + ": " + rejected);
            }
        }
    }

    private void sampleClock(long nowMillis) {
        if (clock == null || clock.outstandingCount() >= 16) {
            return;
        }
        if (lastClockSampleMillis == Long.MIN_VALUE
                || nowMillis - lastClockSampleMillis >= CLOCK_SAMPLE_MILLIS) {
            transport.send(DuelClockSync.encode(clock.begin(nowMillis)));
            lastClockSampleMillis = nowMillis;
        }
    }

    private void handleClock(byte[] message, long nowMillis) {
        try {
            if (clock == null) {
                throw new IllegalArgumentException("clock sync is not configured");
            }
            Object decoded = DuelClockSync.decode(message);
            if (!(decoded instanceof DuelClockSync.Reply)) {
                throw new IllegalArgumentException("guest expected a clock reply");
            }
            clock.accept((DuelClockSync.Reply) decoded, nowMillis);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            rejectedEvents++;
        }
    }

    public int rejectedEvents() { return rejectedEvents; }
}
