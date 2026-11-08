package redcon.net;

import java.io.IOException;
import java.net.InetSocketAddress;

/** Host-side Pair Request pump on the shared discovery/lobby socket. */
public final class UdpPairingHost {

    private final UdpDiscoverySocket socket;
    private final DuelPairingCoordinator coordinator;
    private final long matchId;
    private final long transportSessionId;
    private final long sessionSeed;
    private final long rulesRevision;
    private int rejectedMessages;
    private DuelPairingMessage.Ok accepted;
    private java.net.InetSocketAddress acceptedPeer;
    private DuelHandshake acceptedHandshake;

    public UdpPairingHost(UdpDiscoverySocket socket,
                          DuelPairingCoordinator coordinator,
                          long matchId, long transportSessionId,
                          long sessionSeed, long rulesRevision) {
        if (socket == null || coordinator == null || matchId == 0
                || transportSessionId == 0 || rulesRevision < 0) {
            throw new IllegalArgumentException("invalid UDP pairing host");
        }
        this.socket = socket;
        this.coordinator = coordinator;
        this.matchId = matchId;
        this.transportSessionId = transportSessionId;
        this.sessionSeed = sessionSeed;
        this.rulesRevision = rulesRevision;
    }

    /** Resolve every waiting request and repeat the cached result for retries. */
    public void update() throws IOException {
        for (UdpDiscoverySocket.PairingDatagram datagram
                : socket.pollPairing()) {
            if (!(datagram.message instanceof DuelPairingMessage.Request)) {
                rejectedMessages++;
                continue;
            }
            DuelPairingMessage.Request request =
                (DuelPairingMessage.Request) datagram.message;
            // A NAT may rewrite the UDP source port even though the peer's
            // explicitly advertised listening port is reachable. Discovery
            // already treats that advertised port as authoritative; pairing
            // must do the same or Android emulators can discover one another
            // but every Pair Request is rejected at this boundary.
            InetSocketAddress peer = new InetSocketAddress(
                datagram.source.getAddress(), request.requester.listenPort);
            System.err.println("duel pairing host maps " + datagram.source
                + " to advertised " + peer);
            try {
                DuelPairingCoordinator.Result result = coordinator.handle(
                    request, matchId, transportSessionId,
                    sessionSeed, rulesRevision);
                socket.sendPairing(peer, result.response);
                if (result.ok != null) {
                    socket.sendPairing(peer, result.ok);
                    // The coordinator replays a cached accept for a retried
                    // request, so record the first one only: a later replay is
                    // the same guest and the same session ids.
                    if (accepted == null) {
                        accepted = result.ok;
                        acceptedPeer = peer;
                        acceptedHandshake = request.requester;
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                rejectedMessages++;
            }
        }
    }

    public int rejectedMessages() { return rejectedMessages; }

    /** The accepted PAIR_OK, or null while nobody has been let in. */
    public DuelPairingMessage.Ok accepted() { return accepted; }

    /** The accepted guest's lobby endpoint, which is also its match port. */
    public java.net.InetSocketAddress acceptedPeer() { return acceptedPeer; }

    /** The accepted guest's handshake, for the commander row on Pairing. */
    public DuelHandshake acceptedHandshake() { return acceptedHandshake; }
}
