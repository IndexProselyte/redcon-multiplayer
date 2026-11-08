package redcon.net;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import redcon.desktop.Duel;
import redcon.duel.DuelMatch;
import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.duel.Order;
import redcon.duel.Side;

/**
 * The product lifecycle over a real reliable-UDP peer session.
 *
 * <p>This is {@link InProcessDuelRuntime}'s contract with the second seat
 * removed. Only the local player's actions are authored here; the opponent's
 * arrive as committed events and are applied by the same
 * {@link DuelSessionEventConsumer}. Nothing in the product shell distinguishes
 * the two, which is what makes local play unable to hide a broken remote
 * path.</p>
 *
 * <h2>Where the session comes from</h2>
 *
 * <p>A networked duel settles its rules before it has a session. Both clients
 * sit on the Pairing screen with the match transport already live, exchange
 * {@code RULES_PROPOSE}, and the host orders each into a committed
 * {@code RULES_SET}. The host then starts the match, and the committed
 * {@code PHASE_CLOCK(BUILD, 1)} is the instant both devices construct their own
 * {@link DuelSession} from the agreed rules. The pre-session events are reduced
 * by {@link #peerState()}, and the session consumer adopts the history from
 * that sequence rather than seeing the whole run-up as a gap.</p>
 *
 * <h2>What only the host may do</h2>
 *
 * <p>Rules ordering, the three phase clocks, and the exact round result. The
 * guest declares the defeat of its own fortress and waits: the host holds the
 * result until the coordinator has every declaration the ending requires, which
 * is why {@link #update()} retries a pending commit instead of assuming the
 * peer's evidence has already crossed the wire.</p>
 */
public final class NetworkDuelRuntime implements DuelRuntime {

    /** Battle ticks between defender-authored fort corrections. */
    public static final long CORRECTION_INTERVAL_TICKS = Duel.TICKS_PER_SECOND;

    /** Builds this device's session once the rules are committed. */
    public interface SessionFactory {
        DuelSession create(DuelEvent.RulesSet rules);
    }

    private final int localPlayer;
    private final boolean host;
    private final long matchId;
    private final long sessionSeed;
    private final UdpPeerTransport transport;
    private final SessionFactory factory;
    private final DuelBattleEventRouter battleRouter =
        new DuelBattleEventRouter();
    private final DuelPeerState peerState;
    private final DuelEventEmitter local;

    /** Host only: the committed channel and its authority. */
    private final InProcessEventChannel commits;
    private final DuelSessionCoordinator coordinator;
    private final UdpDuelHostPump hostPump;

    /** Guest only. */
    private final UdpDuelClientPump clientPump;
    private final DuelClockSync.Estimator clockSync;

    private DuelSession session;
    private DuelSessionEventConsumer productConsumer;
    private long timelineTick;
    private long lastCorrectionTick = Long.MIN_VALUE;
    private boolean buildExpired;
    private boolean declaredDefeat;
    private DuelSession.RoundResult pendingResult;
    private Link link = Link.LIVE;
    private String linkDetail = "";
    private final LongSupplier clock;
    private long startMillis = Long.MIN_VALUE;
    private boolean closed;
    private DuelLockstep lockstep;
    private boolean battleFramePending;
    private long battleStartTick = -1;
    private long phaseDeadlineMillis = -1;
    private final Map<Long, Long> localDigests = new HashMap<>();
    private final Map<Long, Long> remoteDigests = new HashMap<>();
    private final Map<Long, String> checkpointReports = new HashMap<>();
    private long lastAgreedDigestFrame = -1;
    private byte[] latestDigestPacket;

    /**
     * @param localPlayer {@link Side#NEAR} for the host, {@link Side#FAR} for
     *                    the guest; pairing fixes this before a single event
     *                    is authored.
     * @param transport   an already-connected reliable session, normally handed
     *                    over by the lobby after PAIR_OK.
     */
    public NetworkDuelRuntime(int localPlayer, boolean host, long matchId,
                              UdpPeerTransport transport,
                              SessionFactory factory) {
        this(localPlayer, host, matchId, 0x524544434f4eL,
             transport, factory, null);
    }

    /**
     * @param clock the platform's millisecond clock. Null uses this process's
     *              monotonic one; a harness supplies its own so heartbeat and
     *              disconnect timing can be exercised without waiting for it.
     */
    public NetworkDuelRuntime(int localPlayer, boolean host, long matchId,
                              UdpPeerTransport transport,
                              SessionFactory factory, LongSupplier clock) {
        this(localPlayer, host, matchId, 0x524544434f4eL,
             transport, factory, clock);
    }

    public NetworkDuelRuntime(int localPlayer, boolean host, long matchId,
                              long sessionSeed, UdpPeerTransport transport,
                              SessionFactory factory, LongSupplier clock) {
        DuelEvent.requirePlayer(localPlayer);
        if (matchId == 0 || transport == null || factory == null) {
            throw new IllegalArgumentException(
                "match id, transport, and session factory are required");
        }
        if (host != (localPlayer == Side.NEAR)) {
            throw new IllegalArgumentException(
                "the pairing host plays the near fortress");
        }
        this.localPlayer = localPlayer;
        this.host = host;
        this.matchId = matchId;
        this.sessionSeed = sessionSeed;
        this.transport = transport;
        this.factory = factory;
        this.clock = clock;
        this.peerState = new DuelPeerState(localPlayer);

        DuelEventConsumer inbound = this::apply;
        if (host) {
            commits = new InProcessEventChannel();
            commits.connect(inbound);
            commits.connect(new UdpCommittedEventSender(matchId, transport));
            coordinator = new DuelSessionCoordinator(localPlayer, commits);
            hostPump = new UdpDuelHostPump(matchId, transport, coordinator);
            clientPump = null;
            clockSync = null;
            local = new DuelEventEmitter(localPlayer, coordinator);
        } else {
            commits = null;
            coordinator = null;
            hostPump = null;
            clockSync = new DuelClockSync.Estimator();
            clientPump = new UdpDuelClientPump(
                matchId, transport, inbound, clockSync);
            local = new DuelEventEmitter(
                localPlayer, new UdpDuelRequestSink(matchId, transport));
        }
    }

    // ---------------------------------------------------------------- lobby

    /**
     * Offer a complete rule set. Legal on either client until Build starts;
     * the host orders competing proposals into one committed revision.
     */
    public void proposeRules(int creditBudget, int buildSeconds,
                             int matchSeconds) {
        requireLive();
        local.proposeRules(creditBudget, buildSeconds, matchSeconds);
    }

    /** Host only: freeze the rules and open round one's Build. */
    public void beginBuild() {
        requireLive();
        if (!host) {
            throw new IllegalStateException("only the host starts the match");
        }
        if (peerState.rules() == null) {
            throw new IllegalStateException("no committed rules to build under");
        }
        if (session != null) {
            throw new IllegalStateException("Build has already started");
        }
        int seconds = peerState.rules().buildSeconds;
        local.setClock(1, DuelEvent.ClockPhase.BUILD, 0,
                       (long) seconds * Duel.TICKS_PER_SECOND, 0,
                       nowMillis() + seconds * 1000L);
    }

    /** The committed rules both clients agree on, or null before the first. */
    public DuelEvent.RulesSet rules() { return peerState.rules(); }

    /** True once the committed Build clock has constructed this device's session. */
    public boolean started() { return session != null; }

    public DuelPeerState peerState() { return peerState; }

    public long matchId() { return matchId; }

    public InetSocketAddress localAddress() { return transport.localAddress(); }

    /** Guest only: the measured offset onto the host's phase-clock timeline. */
    public DuelClockSync.Estimator clockSync() { return clockSync; }

    /**
     * Packets this device refused.
     *
     * <p>It should stay at zero for the life of a duel. A non-zero count means
     * a valid committed event was rejected - a gap, a phase the coordinator
     * disagreed about, or an adapter that was not ready - and after one of
     * those this device stops applying the history at all.</p>
     */
    public int rejectedPackets() {
        return host ? hostPump.rejectedRequests() : clientPump.rejectedEvents();
    }

    // -------------------------------------------------------------- runtime

    @Override public int localPlayer() { return localPlayer; }

    @Override public boolean isHost() { return host; }

    @Override public boolean standIn() { return false; }

    @Override
    public long simulationSeed() {
        // SplitMix64 finalizer gives every round a distinct, reproducible seed;
        // REDCON's own LCG consumes the low 48 bits.
        long round = session == null ? 1L : session.round();
        long value = sessionSeed + round * 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (value ^ (value >>> 31)) & ((1L << 48) - 1L);
    }

    @Override public DuelSession session() { return session; }

    @Override public long timelineTick() { return timelineTick; }

    @Override public Link link() { return link; }

    /** Why the link is not {@link Link#LIVE}; empty while it is. */
    public String linkDetail() { return linkDetail; }

    /**
     * Service only the UDP transport, without applying committed game events.
     *
     * <p>The product shell calls this from a small daemon while a network
     * match exists. REDCON can spend several seconds inside a native builder
     * interaction or platform callback; tying heartbeats exclusively to the
     * next rendered frame makes that harmless UI pause look like a dead peer.
     * Received reliable messages remain queued for {@link #update()} on the
     * game thread, so no session or simulation state crosses threads.</p>
     */
    public void maintainTransport() throws IOException {
        transport.update(nowMillis());
    }

    @Override
    public void update() {
        if (closed) {
            return;
        }
        long now = nowMillis();
        try {
            if (lockstep != null && session != null
                    && session.phase() == DuelSession.Phase.BATTLE) {
                transport.sendUnreliable(
                    LockstepInputCodec.encode(lockstep.outbound()));
                if (latestDigestPacket != null) {
                    transport.sendUnreliable(latestDigestPacket);
                }
            }
            if (host) {
                hostPump.update(now);
            } else {
                clientPump.update(now);
            }
            for (byte[] packet : transport.drainUnreliable()) {
                int magic = packet.length < 4 ? 0
                    : java.nio.ByteBuffer.wrap(packet).getInt();
                if (magic == LockstepInputCodec.MAGIC) {
                    LockstepInputPacket decoded =
                        LockstepInputCodec.decode(packet);
                    if (lockstep != null) lockstep.accept(decoded);
                } else if (magic == LockstepDigestCodec.MAGIC) {
                    acceptDigest(LockstepDigestCodec.decode(packet));
                }
            }
        } catch (IOException failed) {
            fail(Link.LOST, "socket failed: " + failed.getMessage());
            return;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            fail(Link.DESYNC, "invalid lockstep stream: "
                + invalid.getMessage());
            return;
        }
        if (link == Link.LIVE && transport.disconnected(now)) {
            fail(Link.LOST, "no traffic from the peer");
            return;
        }
        // The host's own decisions are taken here rather than inside apply().
        // A committed event is delivered while the channel is mid-broadcast,
        // and publishing a second event from inside that call would hand the
        // socket sender a later sequence before the earlier one. Each of these
        // is guarded by the coordinator's phase, so a repeat is a no-op.
        maybeCommence();
        maybeRebuild();
        commitPendingResult();
    }

    @Override
    public void commitBuild(int player, FortDesign design) {
        requireLocal(player);
        requireLive();
        local.commitBuild(design);
    }

    @Override
    public void setReady(int player, boolean ready) {
        requireLocal(player);
        requireLive();
        local.setReady(ready);
        maybeCommence();
    }

    @Override
    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        requireSession();
        if (session.phase() == DuelSession.Phase.BUILD) {
            ticks = sharedAdvance(session.buildTicksRemaining(), ticks);
            // Holding at the deadline is normal here: this device has already
            // committed and is waiting for the peer's own commit to cross the
            // wire. Advancing into it would let DuelSession commence on one
            // machine before the shared history says so.
            long remaining = session.buildTicksRemaining();
            if (ticks >= remaining) {
                timelineTick += remaining;
                return;
            }
            session.advance(ticks);
            timelineTick += ticks;
            return;
        }
        if (session.phase() == DuelSession.Phase.COMMENCING) {
            ticks = sharedAdvance(session.commencingTicksRemaining(), ticks);
            long remaining = session.commencingTicksRemaining();
            if (ticks < remaining) {
                session.advance(ticks);
                timelineTick += ticks;
                return;
            }
            timelineTick += remaining;
            if (host) {
                int seconds = session.matchSeconds();
                local.setClock(session.round(), DuelEvent.ClockPhase.BATTLE,
                    timelineTick, timelineTick + (long) seconds
                        * Duel.TICKS_PER_SECOND, chooseInputDelay(), -1);
            }
            timelineTick += ticks - remaining;
            return;
        }
        timelineTick += ticks;
    }

    /**
     * The input delay to commit on the round that is about to start.
     *
     * <p>Host only, and chosen once per round because that is the natural
     * boundary a degrading connection can adapt at without surprising a player
     * mid-fight (Q1). Only the guest measures a round trip - the host answers
     * probes rather than sending them - so the measurement the host reads here
     * is the one the guest reported on its own clock probes. Until the guest
     * has a sample, the documented default stands, and both seats read the
     * committed value from the BATTLE clock either way.</p>
     */
    private int chooseInputDelay() {
        long roundTrip = reportedRoundTripMillis();
        return roundTrip < 0
            ? DuelLockstep.DEFAULT_DELAY
            : DuelLockstep.delayForRoundTrip(roundTrip);
    }

    /**
     * The peer round trip this device knows about, or -1 when it has none.
     * The host learns it from the guest's probes; the guest measures it.
     */
    public long reportedRoundTripMillis() {
        if (host) return hostPump.reportedRoundTripMillis();
        return clockSync.ready() ? clockSync.bestSample().roundTripMillis : -1;
    }

    @Override
    public boolean phaseDeadlinePassed() {
        if (phaseDeadlineMillis < 0) {
            return false;
        }
        long hostNow = host || clockSync == null || !clockSync.ready()
            ? nowMillis() : clockSync.hostTimeMillis(nowMillis());
        return hostNow >= phaseDeadlineMillis;
    }

    /** Consume phase time from the committed host deadline, never render rate. */
    private long sharedAdvance(long currentRemaining, long fallbackTicks) {
        if (phaseDeadlineMillis < 0) return fallbackTicks;
        long hostNow = host || clockSync == null || !clockSync.ready()
            ? nowMillis() : clockSync.hostTimeMillis(nowMillis());
        long millis = Math.max(0, phaseDeadlineMillis - hostNow);
        long desired = Math.min(currentRemaining,
            (millis * Duel.TICKS_PER_SECOND + 999L) / 1000L);
        return Math.max(0, currentRemaining - desired);
    }

    @Override
    public void expireBuild(int player, FortDesign design, long ticks) {
        requireLocal(player);
        requireSession();
        if (session.phase() != DuelSession.Phase.BUILD) {
            throw new IllegalArgumentException("Build is not the current phase");
        }
        if (buildExpired) {
            // The peer has not committed yet. Keep the shared timeline moving
            // without re-authoring this device's commit once a frame.
            timelineTick += ticks;
            return;
        }
        if (ticks < session.buildTicksRemaining() && !phaseDeadlinePassed()) {
            // Past the committed deadline the remaining time stops falling,
            // so a frame's ticks need not exceed it: the deadline itself is
            // the crossing.
            throw new IllegalArgumentException(
                "Build expiry must cross the current deadline");
        }
        timelineTick += session.buildTicksRemaining();
        buildExpired = true;
        commitBuild(player, design);
        setReady(player, true);
    }

    @Override
    public void order(int player, Order order) {
        requireLocal(player);
        requireLive();
        if (lockstep == null || session == null
                || session.phase() != DuelSession.Phase.BATTLE) {
            throw new IllegalStateException("lockstep Battle is not ready");
        }
        lockstep.schedule(order);
    }

    @Override
    public boolean beginBattleFrame() {
        requireSession();
        if (link != Link.LIVE || session.phase() != DuelSession.Phase.BATTLE || lockstep == null) {
            return false;
        }
        if (battleFramePending) throw new IllegalStateException("previous battle frame is unfinished");
        if (!lockstep.ready()) return false;
        long frame = lockstep.nextFrame();
        battleFramePending = true;
        try {
            for (LockstepInputFrame input : lockstep.peek()) {
                for (Order order : input.orders()) {
                    battleRouter.applyOrder(new DuelEvent.OrderIssued(
                        input.player, battleStartTick + frame * Duel.TICKS_PER_FRAME,
                        order));
                }
            }
        } catch (RuntimeException | Error failure) {
            abortBattleFrame(failure);
            throw failure;
        }
        return true;
    }

    @Override
    public void completeBattleFrame() {
        requireLive();
        if (!battleFramePending) throw new IllegalStateException("no battle frame is pending");
        lockstep.complete();
        battleFramePending = false;
        timelineTick = battleStartTick
            + lockstep.nextFrame() * Duel.TICKS_PER_FRAME;
    }

    @Override
    public void abortBattleFrame(Throwable failure) {
        if (link != Link.LIVE) return;
        failure.printStackTrace();
        String detail = "simulation failed at frame " + lockstepFrame() + ": " + failure;
        try {
            // Use the existing reliable leave path so the peer cannot remain
            // live forever receiving heartbeats at an impossible barrier.
            leave(localPlayer, detail);
        } catch (RuntimeException notificationFailure) {
            failure.addSuppressed(notificationFailure);
        } finally {
            fail(Link.DESYNC, detail);
        }
    }

    public long lockstepFrame() {
        return lockstep == null ? -1 : lockstep.nextFrame();
    }

    public long lastAgreedDigestFrame() { return lastAgreedDigestFrame; }

    @Override
    public void fire(int player, long shotId, int weaponId, int ordnanceType,
                     float aimX, float aimY) {
        requireLocal(player);
        requireLive();
        if (redcon.desktop.DuelIdentity.flag("redcon.lockstepTelemetry")) {
            local.fire(timelineTick, shotId, weaponId, ordnanceType, aimX, aimY);
        }
    }

    @Override
    public void impact(int player, long shotId, int roomId, float damage,
                       DuelEvent.ImpactEffect... effects) {
        requireLocal(player);
        requireLive();
        if (redcon.desktop.DuelIdentity.flag("redcon.lockstepTelemetry")) {
            local.impact(timelineTick, shotId, roomId, damage, effects);
        }
    }

    @Override
    public void synchronizeFort(int player, int[] roomIds, float[] hitPoints,
                                int[] states) {
        requireLocal(player);
        requireLive();
        local.synchronizeFort(timelineTick, roomIds, hitPoints, states);
        lastCorrectionTick = timelineTick;
    }

    /** True when this device owes the peer another correction for its fort. */
    public boolean correctionDue() {
        return session != null && session.phase() == DuelSession.Phase.BATTLE
            && link == Link.LIVE
            && (lastCorrectionTick == Long.MIN_VALUE
                || timelineTick - lastCorrectionTick >= CORRECTION_INTERVAL_TICKS);
    }

    @Override
    public void checkpointDigest(int player, long digest) {
        requireLocal(player);
        requireLive();
        if (battleFramePending) throw new IllegalStateException("cannot digest an unfinished frame");
        if (lockstep == null || lockstep.confirmedFrame() < 0) return;
        long frame = lockstep.confirmedFrame();
        localDigests.put(frame, digest);
        checkpointReports.put(frame, "checkpoint frame=" + frame
            + " round=" + session.round() + " seed="
            + Long.toHexString(simulationSeed()) + " build="
            + redcon.desktop.DuelIdentity.BUILD_VERSION + "\n"
            + battleRouter.stateReport() + "\n" + lockstep.diagnosticHistory());
        lastCorrectionTick = timelineTick;
        latestDigestPacket = LockstepDigestCodec.encode(
            new LockstepDigestPacket(matchId, session.round(), player, frame, digest));
        transport.sendUnreliable(latestDigestPacket);
        compareDigest(frame);
        discardDigests(frame - 120);
    }

    private void acceptDigest(LockstepDigestPacket packet) {
        if (packet.matchId != matchId || packet.player == localPlayer) {
            throw new IllegalArgumentException("foreign lockstep digest");
        }
        if (session == null || session.phase() != DuelSession.Phase.BATTLE
                || packet.round != session.round()) return;
        Long prior = remoteDigests.putIfAbsent(packet.frame, packet.digest);
        if (prior != null && prior.longValue() != packet.digest) {
            throw new IllegalStateException(
                "peer changed an already-declared state digest");
        }
        compareDigest(packet.frame);
    }

    private void compareDigest(long frame) {
        Long localDigest = localDigests.get(frame);
        Long remoteDigest = remoteDigests.get(frame);
        if (localDigest != null && remoteDigest != null
                && !localDigest.equals(remoteDigest)) {
            String detail = "desync at frame " + frame + ": local="
                + Long.toUnsignedString(localDigest, 16) + " remote="
                + Long.toUnsignedString(remoteDigest, 16);
            System.err.println("duel " + detail);
            echoLocalState(frame);
            writeDesyncReport(frame, localDigest, remoteDigest, detail);
            fail(Link.DESYNC, detail);
        } else if (localDigest != null && remoteDigest != null) {
            lastAgreedDigestFrame = Math.max(lastAgreedDigestFrame, frame);
        }
    }

    /**
     * Put this device's state where a handset's operator can actually read it.
     *
     * <p>The report file is the better artefact, but on Android it lands in
     * the application's private cache, which is out of reach without a
     * debuggable build or a rooted device. A desync is a teardown and happens
     * once, so the same detail goes to the log, where {@code adb logcat} has
     * it - and where the desktop peer's console already shows its own.</p>
     */
    private void echoLocalState(long frame) {
        String state = checkpointReports.getOrDefault(frame,
            "checkpoint state unavailable for frame " + frame + "\n");
        if (state.isEmpty()) {
            return;
        }
        for (String line : state.split("\n")) {
            if (!line.isEmpty()) {
                System.err.println("duel state " + line);
            }
        }
    }

    private void writeDesyncReport(long frame, long localDigest,
                                   long remoteDigest, String detail) {
        redcon.desktop.DuelAppLog.record("DESYNC match=" + Long.toUnsignedString(matchId)
            + " player=" + localPlayer + " round=" + session.round() + " seed=" + simulationSeed()
            + " lastAgreedFrame=" + lastAgreedDigestFrame + " " + detail, 0xffffa080);
        String root = System.getProperty("redcon.desyncReportDir",
            System.getProperty("java.io.tmpdir", "."));
        java.io.File report = new java.io.File(root,
            "redcon-desync-" + Long.toUnsignedString(matchId) + "-"
                + localPlayer + "-" + frame + ".txt");
        try (PrintWriter out = new PrintWriter(report, "UTF-8")) {
            out.println("REDCON deterministic lockstep desync");
            out.println("match=" + Long.toUnsignedString(matchId));
            out.println("player=" + localPlayer);
            out.println("frame=" + frame);
            out.println("local=" + Long.toUnsignedString(localDigest, 16));
            out.println("remote=" + Long.toUnsignedString(remoteDigest, 16));
            out.println("detail=" + detail);
            out.println();
            String state = checkpointReports.getOrDefault(frame,
                "checkpoint state unavailable for frame " + frame + "\n");
            if (!state.isEmpty()) {
                out.println();
                out.print(state);
            }
            System.err.println("duel desync report " + report.getAbsolutePath());
        } catch (IOException failed) {
            System.err.println("duel could not write desync report: "
                + failed.getMessage());
        }
    }

    private void discardDigests(long before) {
        localDigests.keySet().removeIf(frame -> frame < before);
        remoteDigests.keySet().removeIf(frame -> frame < before);
        checkpointReports.keySet().removeIf(frame -> frame < before);
    }

    @Override
    public void finishRound(DuelSession.RoundResult result) {
        requireSession();
        if (result == null || session.phase() != DuelSession.Phase.BATTLE) {
            throw new IllegalArgumentException("live round result is required");
        }
        if (link != Link.LIVE) {
            return;
        }
        boolean lost = result.ending == DuelMatch.Ending.SIMULTANEOUS
            || (result.ending == DuelMatch.Ending.DEFEAT
                && result.winner != localPlayer);
        if (lost && !declaredDefeat) {
            declaredDefeat = true;
            local.defeated(timelineTick);
        }
        if (host && pendingResult == null) {
            pendingResult = result;
            commitPendingResult();
        }
    }

    @Override
    public void setRematchReady(int player, boolean ready) {
        requireLocal(player);
        requireLive();
        local.setRematchReady(ready);
        maybeRebuild();
    }

    @Override
    public void leave(int player, String reason) {
        requireLocal(player);
        if (closed || link != Link.LIVE) {
            return;
        }
        if (session == null || session.phase() != DuelSession.Phase.CLOSED) {
            local.leave(reason);
            drain();
        }
    }

    @Override
    public void attachBattleConsumer(DuelBattleEventConsumer consumer) {
        battleRouter.attach(consumer);
    }

    @Override
    public void detachBattleConsumer(DuelBattleEventConsumer consumer) {
        battleRouter.detach(consumer);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        transport.close();
    }

    // ---------------------------------------------------------------- inner

    /**
     * Push whatever is queued out of the send window.
     *
     * <p>LEAVE is the one event whose delivery cannot wait for the next product
     * frame, because there is not going to be one: the shell tears the duel
     * down immediately afterwards. The reliable channel still owns
     * retransmission, so this is a flush, not a second delivery path.</p>
     */
    private void drain() {
        long now = nowMillis();
        for (int attempt = 0; attempt < 8 && !transport.isIdle(); attempt++) {
            try {
                if (host) {
                    hostPump.update(now + attempt);
                } else {
                    clientPump.update(now + attempt);
                }
            } catch (IOException ignored) {
                return;
            }
        }
    }

    /** Apply one committed event, creating the session at the first Build clock. */
    private void apply(CommittedDuelEvent committed) {
        DuelEvent event = committed.event();
        if (event.kind() == DuelEvent.Kind.PHASE_CLOCK
                || event.kind() == DuelEvent.Kind.READY_SET
                || event.kind() == DuelEvent.Kind.LEAVE) {
            System.err.println("duel apply #" + committed.sequence() + " "
                + event.kind()
                + (event.kind() == DuelEvent.Kind.PHASE_CLOCK
                    ? " " + ((DuelEvent.PhaseClock) event).phase : ""));
        }
        if (session == null && event.kind() == DuelEvent.Kind.PHASE_CLOCK
                && ((DuelEvent.PhaseClock) event).phase
                    == DuelEvent.ClockPhase.BUILD) {
            startSession(committed.sequence() - 1);
        }
        peerState.accept(committed.copyForDelivery());
        if (productConsumer != null) {
            productConsumer.accept(committed);
        }
        if (event.kind() == DuelEvent.Kind.LEAVE
                && ((DuelEvent.Leave) event).player != localPlayer) {
            link = Link.PEER_LEFT;
            linkDetail = ((DuelEvent.Leave) event).reason;
        }
        if (event.kind() == DuelEvent.Kind.PHASE_CLOCK) {
            phaseDeadlineMillis = ((DuelEvent.PhaseClock) event)
                .hostDeadlineMillis;
        }
        if (event.kind() == DuelEvent.Kind.PHASE_CLOCK
                && ((DuelEvent.PhaseClock) event).phase
                    == DuelEvent.ClockPhase.BUILD) {
            buildExpired = false;
            lockstep = null;
            battleFramePending = false;
            latestDigestPacket = null;
            declaredDefeat = false;
            pendingResult = null;
            lastCorrectionTick = Long.MIN_VALUE;
        }
        if (event.kind() == DuelEvent.Kind.PHASE_CLOCK
                && ((DuelEvent.PhaseClock) event).phase
                    == DuelEvent.ClockPhase.BATTLE) {
            DuelEvent.PhaseClock battleClock = (DuelEvent.PhaseClock) event;
            phaseDeadlineMillis = battleClock.hostDeadlineMillis;
            battleStartTick = battleClock.startTick;
            timelineTick = battleStartTick;
            lockstep = new DuelLockstep(
                matchId, session.round(), localPlayer, battleClock.inputDelay);
            battleFramePending = false;
            localDigests.clear();
            remoteDigests.clear();
            checkpointReports.clear();
            lastAgreedDigestFrame = -1;
            latestDigestPacket = null;
        }
    }

    private void startSession(long adoptedSequence) {
        DuelEvent.RulesSet rules = peerState.rules();
        if (rules == null) {
            throw new IllegalStateException(
                "Build started before any rules were committed");
        }
        DuelSession created = factory.create(rules);
        if (created == null || created.round() != 1
                || created.phase() != DuelSession.Phase.BUILD
                || created.creditBudget() != rules.creditBudget
                || created.buildSeconds() != rules.buildSeconds
                || created.matchSeconds() != rules.matchSeconds) {
            throw new IllegalStateException(
                "the session factory ignored the committed rules");
        }
        session = created;
        productConsumer = new DuelSessionEventConsumer(
            localPlayer, created, battleRouter, adoptedSequence);
    }

    /** Host only: commence once the committed history has both ready forts. */
    private void maybeCommence() {
        if (!host || session == null
                || session.phase() != DuelSession.Phase.COMMENCING
                || coordinator.phase() != DuelSessionCoordinator.Phase.BUILD) {
            return;
        }
        int seconds = DuelSession.COMMENCING_SECONDS;
        local.setClock(session.round(), DuelEvent.ClockPhase.COMMENCING,
            timelineTick, timelineTick + (long) seconds
                * Duel.TICKS_PER_SECOND, 0, -1);
    }

    /** Host only: open the next round once both rematch votes are committed. */
    private void maybeRebuild() {
        if (!host || session == null
                || session.phase() != DuelSession.Phase.BUILD
                || coordinator.phase() != DuelSessionCoordinator.Phase.RESULT) {
            return;
        }
        int seconds = session.buildSeconds();
        local.setClock(session.round(), DuelEvent.ClockPhase.BUILD,
            timelineTick, timelineTick + (long) seconds * Duel.TICKS_PER_SECOND,
            0, nowMillis() + seconds * 1000L);
    }

    /**
     * Retry the host's exact result until the peer's evidence has arrived.
     *
     * <p>{@code DuelSessionCoordinator} refuses a defeat result before the
     * losing player has declared it, and that declaration is a packet in
     * flight. Retrying is the honest shape: the host does not invent the
     * missing evidence, and it does not commit a different ending because the
     * first attempt was early.</p>
     */
    private void commitPendingResult() {
        if (!host || pendingResult == null || session == null
                || session.phase() != DuelSession.Phase.BATTLE
                || link != Link.LIVE) {
            return;
        }
        try {
            local.roundResult(pendingResult);
        } catch (IllegalStateException notYet) {
            return;
        }
        pendingResult = null;
    }

    private void fail(Link cause, String detail) {
        if (link != Link.LIVE) {
            return;
        }
        link = cause;
        linkDetail = detail;
        System.err.println("duel link " + cause + ": " + detail);
        if (session != null && session.phase() != DuelSession.Phase.CLOSED) {
            // The duel ends explicitly on this device. It is never left
            // apparently live while the peer is gone.
            session.leave(localPlayer ^ 1);
        }
    }

    private void requireLocal(int player) {
        if (player != localPlayer) {
            throw new IllegalArgumentException("this device plays player "
                + localPlayer + " and cannot author player " + player);
        }
    }

    private void requireSession() {
        if (session == null) {
            throw new IllegalStateException(
                "the committed Build clock has not arrived");
        }
    }

    private void requireLive() {
        if (closed) {
            throw new IllegalStateException("the duel runtime is closed");
        }
        if (link != Link.LIVE) {
            throw new IllegalStateException("the peer link is " + link);
        }
    }

    private long nowMillis() {
        if (clock != null) {
            return clock.getAsLong();
        }
        long millis = System.nanoTime() / 1_000_000L;
        if (startMillis == Long.MIN_VALUE) {
            startMillis = millis;
        }
        return millis - startMillis;
    }
}
