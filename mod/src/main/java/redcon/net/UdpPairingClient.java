package redcon.net;

import java.io.IOException;
import java.net.InetSocketAddress;

/** Retrying guest-side Pair Request state over the unreliable lobby socket. */
public final class UdpPairingClient {

    public static final long RETRY_MILLIS = 250;
    public static final long TIMEOUT_MILLIS = 5000;

    public enum State {
        WAITING,
        PAIRED,
        DECLINED,
        BUSY,
        INCOMPATIBLE,
        TIMED_OUT
    }

    private final UdpDiscoverySocket socket;
    private final InetSocketAddress hostEndpoint;
    private final DuelPairingMessage.Request request;
    private State state = State.WAITING;
    private DuelPairingMessage.Response response;
    private DuelPairingMessage.Ok ok;
    private long startedMillis = Long.MIN_VALUE;
    private long lastSendMillis = Long.MIN_VALUE;
    private int attempts;
    private int rejectedMessages;

    public UdpPairingClient(UdpDiscoverySocket socket,
                            InetSocketAddress hostEndpoint,
                            DuelPairingMessage.Request request) {
        if (socket == null || hostEndpoint == null
                || hostEndpoint.isUnresolved() || hostEndpoint.getPort() <= 0
                || request == null) {
            throw new IllegalArgumentException(
                "pairing socket, host endpoint, and request are required");
        }
        this.socket = socket;
        this.hostEndpoint = hostEndpoint;
        this.request = request;
    }

    /** Retry the request and consume replies; call from the platform frame pump. */
    public void update(long nowMillis) throws IOException {
        if (state != State.WAITING) {
            return;
        }
        if (startedMillis == Long.MIN_VALUE) {
            startedMillis = nowMillis;
        }
        if (nowMillis < startedMillis) {
            throw new IllegalArgumentException("pairing clock moved backwards");
        }
        for (UdpDiscoverySocket.PairingDatagram datagram
                : socket.pollPairing()) {
            // NAT is allowed to rewrite the host's UDP source port. The
            // response still has to come from the discovered host address;
            // request, host and guest ids below bind it to this attempt.
            if (!hostEndpoint.getAddress().equals(
                    datagram.source.getAddress())) {
                System.err.println("duel pairing reject source "
                    + datagram.source + ", expected host " + hostEndpoint);
                rejectedMessages++;
                continue;
            }
            apply(datagram.message);
            if (state != State.WAITING) {
                break;
            }
        }
        if (state != State.WAITING) {
            return;
        }
        if (nowMillis - startedMillis >= TIMEOUT_MILLIS) {
            state = State.TIMED_OUT;
            return;
        }
        if (lastSendMillis == Long.MIN_VALUE
                || nowMillis - lastSendMillis >= RETRY_MILLIS) {
            socket.sendPairing(hostEndpoint, request);
            lastSendMillis = nowMillis;
            attempts++;
        }
    }

    private void apply(DuelPairingMessage message) {
        if (message instanceof DuelPairingMessage.Response) {
            DuelPairingMessage.Response incoming =
                (DuelPairingMessage.Response) message;
            if (incoming.requestId != request.requestId
                    || incoming.hostInstanceId != request.targetInstanceId) {
                rejectedMessages++;
                return;
            }
            response = incoming;
            switch (incoming.decision) {
                case ACCEPT:
                    return; // PAIR_OK may arrive before or after this datagram.
                case DECLINE:
                    state = State.DECLINED;
                    return;
                case BUSY:
                    state = State.BUSY;
                    return;
                case INCOMPATIBLE:
                    state = State.INCOMPATIBLE;
                    return;
                default:
                    throw new IllegalArgumentException(
                        "unsupported pairing decision " + incoming.decision);
            }
        }
        if (message instanceof DuelPairingMessage.Ok) {
            DuelPairingMessage.Ok incoming = (DuelPairingMessage.Ok) message;
            if (incoming.requestId != request.requestId
                    || incoming.hostInstanceId != request.targetInstanceId
                    || incoming.guestInstanceId != request.requester.instanceId) {
                rejectedMessages++;
                return;
            }
            ok = incoming;
            state = State.PAIRED;
            return;
        }
        rejectedMessages++;
    }

    public State state() { return state; }
    public DuelPairingMessage.Response response() { return response; }
    public DuelPairingMessage.Ok ok() { return ok; }
    public int attempts() { return attempts; }
    public int rejectedMessages() { return rejectedMessages; }
}
