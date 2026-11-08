package redcon.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.function.LongSupplier;
import redcon.duel.Side;

/**
 * Everything a device does before it has a duel: announce, browse, pair.
 *
 * <p>This is the missing half of Stage 1. The reliable session, the coordinator
 * and the product runtime were already transport-independent; what did not
 * exist was the thing that finds a second device, agrees to play it, and hands
 * the resulting endpoints to {@link NetworkDuelRuntime}. It owns exactly one
 * socket, holds no widget, and is stepped from the platform's frame pump.</p>
 *
 * <h2>One port, two jobs, in that order</h2>
 *
 * <p>Discovery announcements and Pair Request traffic share the lobby socket,
 * because a peer's announcement is what tells you the port to address a request
 * to. When a request is accepted, both devices close that socket and rebind the
 * reliable match transport to the same local port, so the endpoints the two
 * clients already know each other by keep working. The host holds its lobby
 * open for a further {@link #PAIRED_GRACE_MILLIS} first: a dropped PAIR_OK is
 * retried by the guest, and the coordinator replays the original decision.</p>
 */
public final class DuelLobby implements AutoCloseable {

    /** The LAN port a REDCON duel announces and listens on. */
    public static final int DEFAULT_PORT = 47810;

    /** How long the host keeps answering retries after accepting. */
    public static final long PAIRED_GRACE_MILLIS = 1000;

    public enum State {
        /** Discovering other devices, without advertising an open game. */
        BROWSING,
        /** Announcing and accepting the first compatible Pair Request. */
        HOSTING,
        /** A Pair Request is outstanding to one chosen peer. */
        JOINING,
        /** Paired; {@link #adopt} can build the match runtime. */
        PAIRED,
        /** The attempt ended without a match; see {@link #failure()}. */
        FAILED
    }

    private final UdpDiscoverySocket socket;
    private final InetSocketAddress announceTarget;
    private final DuelPeerDirectory directory;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();

    private DuelHandshake local;
    private State state = State.BROWSING;
    private String failure = "";

    private DuelPairingCoordinator hostCoordinator;
    private UdpPairingHost hostPump;
    private UdpPairingClient joinClient;
    private InetSocketAddress peerEndpoint;
    private DuelHandshake peerHandshake;
    private DuelPairingMessage.Ok agreement;
    private long acceptedMillis = Long.MIN_VALUE;
    private long matchId;
    private long transportSessionId;
    private long sessionSeed;
    private boolean closed;

    public DuelLobby(InetSocketAddress bind, InetSocketAddress announceTarget,
                     DuelHandshake local, LongSupplier clock)
            throws IOException {
        if (bind == null || announceTarget == null || local == null
                || clock == null) {
            throw new IllegalArgumentException(
                "bind address, announce target, handshake, and clock are required");
        }
        this.socket = new UdpDiscoverySocket(bind);
        this.announceTarget = announceTarget;
        this.clock = clock;
        // The announced port has to be the one actually bound: an ephemeral
        // bind is normal on a machine already running another instance.
        this.local = withState(withPort(local, socket.localAddress().getPort()),
            DuelHandshake.State.BROWSING);
        this.directory = new DuelPeerDirectory(this.local);
    }

    /** Announce, collect peers, and move any outstanding pairing forward. */
    public void update() throws IOException {
        if (closed || state == State.PAIRED && expiredGrace()) {
            return;
        }
        long now = clock.getAsLong();
        for (UdpDiscoverySocket.Announcement announcement
                : socket.update(announceTarget, local, now)) {
            directory.observe(announcement, now);
        }
        switch (state) {
            case HOSTING:
                hostPump.update();
                if (hostPump.accepted() != null) {
                    accept(hostPump.accepted(), hostPump.acceptedPeer(),
                           hostPump.acceptedHandshake(), now);
                }
                return;
            case JOINING:
                joinClient.update(now);
                applyJoinState(now);
                return;
            case PAIRED:
                // Keep replaying the cached decision for a retried request
                // until the guest can only have received it.
                if (hostPump != null) {
                    hostPump.update();
                }
                return;
            default:
                declineUnhostedRequests();
                return;
        }
    }

    /** Peers seen recently, in the stable order the Browse list shows them. */
    public List<DuelPeerDirectory.Peer> peers() {
        return directory.peers(clock.getAsLong());
    }

    /** Advertise this device as open and accept the first compatible request. */
    public void host() {
        requireIdle();
        failure = "";
        local = withState(local, DuelHandshake.State.IDLE);
        matchId = nonZero();
        transportSessionId = nonZero();
        sessionSeed = random.nextLong();
        hostCoordinator = new DuelPairingCoordinator(local);
        hostPump = new UdpPairingHost(socket, hostCoordinator, matchId,
                                      transportSessionId, sessionSeed, 0);
        state = State.HOSTING;
    }

    /** Ask one discovered peer for a duel. */
    public void join(DuelPeerDirectory.Peer peer) {
        requireIdle();
        if (peer == null) {
            throw new IllegalArgumentException("no peer selected");
        }
        if (peer.compatibility != DuelHandshake.Compatibility.COMPATIBLE) {
            fail(peer.compatibility.name());
            return;
        }
        if (peer.handshake.state != DuelHandshake.State.IDLE) {
            fail(peer.handshake.state == DuelHandshake.State.BROWSING
                ? "NOT_HOSTING" : "BUSY");
            return;
        }
        failure = "";
        local = withState(local, DuelHandshake.State.BUSY);
        peerEndpoint = peer.address;
        peerHandshake = peer.handshake;
        joinClient = new UdpPairingClient(socket, peer.address,
            new DuelPairingMessage.Request(
                nonZero(), peer.handshake.instanceId, local));
        state = State.JOINING;
    }

    /** Give up an outstanding request or an advertised game and browse again. */
    public void cancel() {
        if (state == State.PAIRED) {
            throw new IllegalStateException("a paired lobby cannot be cancelled");
        }
        hostCoordinator = null;
        hostPump = null;
        joinClient = null;
        peerEndpoint = null;
        peerHandshake = null;
        failure = "";
        state = State.BROWSING;
        local = withState(local, DuelHandshake.State.BROWSING);
    }

    /**
     * Close the lobby socket and open the agreed match session.
     *
     * <p>The host plays {@link Side#NEAR} and the guest {@link Side#FAR}. That
     * is not a rendering decision - the shell puts the local commander on the
     * side its screen wants - it is which player id this device may author.</p>
     */
    public NetworkDuelRuntime adopt(NetworkDuelRuntime.SessionFactory factory)
            throws IOException {
        if (state != State.PAIRED) {
            throw new IllegalStateException("the lobby has not paired");
        }
        boolean host = hostPump != null;
        InetSocketAddress localAddress = socket.localAddress();
        socket.close();
        UdpPeerTransport transport =
            new UdpPeerTransport(localAddress, agreement.transportSessionId);
        transport.connect(peerEndpoint);
        closed = true;
        return new NetworkDuelRuntime(
            host ? Side.NEAR : Side.FAR, host, agreement.matchId,
            agreement.sessionSeed, transport, factory, clock);
    }

    public State state() { return state; }

    /** Read-only progress for the local connection panel. */
    public int pairingAttempts() { return joinClient == null ? 0 : joinClient.attempts(); }
    public boolean hostAccepted() {
        return joinClient != null && joinClient.response() != null
            && joinClient.response().decision == DuelPairingMessage.Decision.ACCEPT;
    }

    /** Why {@link State#FAILED}; one of the pairing decisions or a timeout. */
    public String failure() { return failure; }

    /** The paired opponent's handshake, for the Pairing screen's commander row. */
    public DuelHandshake peerHandshake() { return peerHandshake; }

    public DuelHandshake localHandshake() { return local; }

    public DuelPairingMessage.Ok agreement() { return agreement; }

    public InetSocketAddress localAddress() { return socket.localAddress(); }

    /**
     * True once {@link #adopt} may be called.
     *
     * <p>Both devices wait out the same grace window, not only the host. The
     * guest has no decision to replay, but it does have a lobby socket bound
     * to the port its match transport is about to take: closing it early would
     * point reliable-channel frames at a host that is still listening for
     * pairing on that port.</p>
     */
    public boolean readyToAdopt() {
        return state == State.PAIRED && expiredGrace();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            socket.close();
        }
    }

    // ----------------------------------------------------------------- inner

    private void accept(DuelPairingMessage.Ok ok, InetSocketAddress endpoint,
                        DuelHandshake guest, long now) {
        agreement = ok;
        peerEndpoint = endpoint;
        peerHandshake = guest;
        acceptedMillis = now;
        state = State.PAIRED;
        // Stop advertising as joinable the moment a seat is taken.
        local = withState(local, DuelHandshake.State.BUSY);
    }

    private void applyJoinState(long now) {
        switch (joinClient.state()) {
            case WAITING:
                return;
            case PAIRED:
                agreement = joinClient.ok();
                acceptedMillis = now;
                state = State.PAIRED;
                local = withState(local, DuelHandshake.State.BUSY);
                return;
            case DECLINED:
                fail("DECLINE");
                return;
            case BUSY:
                fail("BUSY");
                return;
            case INCOMPATIBLE:
                fail(joinClient.response() == null
                     ? "INCOMPATIBLE"
                     : joinClient.response().compatibility.name());
                return;
            case TIMED_OUT:
                fail("TIMEOUT");
                return;
            default:
                throw new IllegalStateException(
                    "unknown pairing state " + joinClient.state());
        }
    }

    private void fail(String reason) {
        failure = reason;
        state = State.FAILED;
        joinClient = null;
        peerEndpoint = null;
        peerHandshake = null;
        local = withState(local, DuelHandshake.State.BROWSING);
    }

    /** A cached READY announcement can outlive Host being cancelled. Reply
     * immediately instead of leaving its guest waiting for NO ANSWER. */
    private void declineUnhostedRequests() throws IOException {
        for (UdpDiscoverySocket.PairingDatagram datagram : socket.pollPairing()) {
            if (!(datagram.message instanceof DuelPairingMessage.Request)) continue;
            DuelPairingMessage.Request request = (DuelPairingMessage.Request) datagram.message;
            if (request.targetInstanceId != local.instanceId) continue;
            DuelHandshake.Compatibility compatibility = local.compatibilityWith(request.requester);
            socket.sendPairing(new InetSocketAddress(datagram.source.getAddress(),
                request.requester.listenPort), new DuelPairingMessage.Response(
                    request.requestId, local.instanceId,
                    compatibility == DuelHandshake.Compatibility.COMPATIBLE
                        ? DuelPairingMessage.Decision.DECLINE : DuelPairingMessage.Decision.INCOMPATIBLE,
                    compatibility));
        }
    }

    private boolean expiredGrace() {
        return acceptedMillis != Long.MIN_VALUE
            && clock.getAsLong() - acceptedMillis >= PAIRED_GRACE_MILLIS;
    }

    private void requireIdle() {
        if (closed) {
            throw new IllegalStateException("the lobby is closed");
        }
        if (state != State.BROWSING && state != State.FAILED) {
            throw new IllegalStateException("the lobby is already " + state);
        }
    }

    private long nonZero() {
        long value = random.nextLong();
        return value == 0 ? 1 : value;
    }

    private static DuelHandshake withPort(DuelHandshake source, int port) {
        return new DuelHandshake(source.protocolVersion, source.instanceId,
            source.nonce, source.playerName, source.buildVersion,
            source.assetHash(), source.state, port);
    }

    private static DuelHandshake withState(DuelHandshake source,
                                           DuelHandshake.State state) {
        return new DuelHandshake(source.protocolVersion, source.instanceId,
            source.nonce, source.playerName, source.buildVersion,
            source.assetHash(), state, source.listenPort);
    }
}
