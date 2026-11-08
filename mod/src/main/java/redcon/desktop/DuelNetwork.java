package redcon.desktop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.net.DuelEvent;
import redcon.net.DuelLobby;
import redcon.net.DuelPeerDirectory;
import redcon.net.DuelRuntime;
import redcon.net.NetworkDuelRuntime;

/**
 * The shell's one live network duel: its lobby, and then its match runtime.
 *
 * <p>Everything here is stepped from the platform's frame pump, on the game
 * thread, exactly like {@link DuelBuildFlow} and {@link DuelBattleFlow}. There
 * is no socket thread and no second gameplay path: this class only decides
 * when the lobby has become a match, and hands that match to the existing
 * product flow.</p>
 */
public final class DuelNetwork {

    private static DuelLobby lobby;
    private static NetworkDuelRuntime runtime;
    private static DuelPairingActivity pairing;
    private static String failure = "";
    private static String nearName = "";
    private static String farName = "";
    private static boolean buildStarted;
    private static volatile NetworkDuelRuntime maintainedRuntime;
    private static Thread transportKeeper;

    private DuelNetwork() {
    }

    /** Open the lobby when the Browse screen appears. Idempotent. */
    public static synchronized void open() {
        if (lobby != null || runtime != null) {
            return;
        }
        try {
            InetSocketAddress bind = new InetSocketAddress(
                DuelIdentity.lobbyPort());
            lobby = new DuelLobby(bind, DuelIdentity.announceTarget(),
                DuelIdentity.local(Math.max(1, DuelIdentity.lobbyPort())),
                DuelNetwork::nowMillis);
            System.err.println("duel lobby " + DuelIdentity.playerName()
                + " bound " + lobby.localAddress() + ", announces to "
                + DuelIdentity.announceTarget());
            failure = "";
        } catch (IOException | RuntimeException failed) {
            lobby = null;
            failure = DuelStrings.of(DuelStrings.UNREACHABLE);
            System.err.println("duel lobby unavailable: " + failed);
            DuelAppLog.error("Lobby unavailable", failed);
        }
    }

    /** Drop the lobby when Browse is left without a match. */
    public static synchronized void close() {
        if (lobby != null) {
            lobby.close();
            lobby = null;
        }
    }

    /** Tear the whole network duel down, including a live match. */
    public static synchronized void reset() {
        close();
        if (runtime != null) {
            stopTransportKeeper();
            runtime.close();
            runtime = null;
        }
        pairing = null;
        buildStarted = false;
        failure = "";
    }

    public static synchronized DuelLobby lobby() { return lobby; }

    public static synchronized NetworkDuelRuntime runtime() { return runtime; }

    public static synchronized String failure() { return failure; }

    /** The paired commander names, near first, as both devices will see them. */
    public static synchronized String nearName() { return nearName; }

    public static synchronized String farName() { return farName; }

    public static synchronized List<DuelPeerDirectory.Peer> peers() {
        return lobby == null ? Collections.<DuelPeerDirectory.Peer>emptyList()
                             : lobby.peers();
    }

    /** Remember which Pairing screen the match should hand Build back to. */
    static synchronized void bindPairing(DuelPairingActivity activity) {
        pairing = activity;
    }

    /**
     * One frame of lobby and pre-Build match traffic.
     *
     * <p>Once Build starts, {@link DuelBuildFlow} and {@link DuelBattleFlow}
     * own the runtime's pump: two callers would drain the socket twice in one
     * frame and deliver the same committed events to the session out of the
     * order the flow reads them in.</p>
     */
    public static synchronized void updateActive() {
        step();
        // The two lobby screens follow this state rather than polling their
        // own sockets, so they are refreshed here, once, after it settles.
        DuelBrowseActivity.refreshActive();
        DuelPairingActivity.refreshActive();
    }

    private static void step() {
        if (lobby != null) {
            try {
                lobby.update();
            } catch (IOException failed) {
                DuelAppLog.error("Lobby update", failed);
                failure = DuelStrings.of(DuelStrings.UNREACHABLE);
                close();
                return;
            }
            if (lobby.state() == DuelLobby.State.FAILED) {
                failure = describe(lobby.failure());
            } else {
                failure = "";
            }
            if (lobby.readyToAdopt()) {
                adopt();
            }
        }
        if (runtime == null || DuelBuildFlow.active() != null
                || DuelBattleFlow.activeSession() != null) {
            return;
        }
        if (buildStarted) {
            // Build has been handed over and given back: the duel is over, by
            // a leave, a lost peer, or a closed session. Drop the match rather
            // than reopening Build on a session that has finished.
            end();
            return;
        }
        runtime.update();
        if (runtime.link() != DuelRuntime.Link.LIVE) {
            end();
            return;
        }
        if (runtime.started() && pairing != null) {
            // The committed Build clock has landed on this device. Both
            // clients reach this on their own; neither waits for the other's
            // screen to have caught up.
            buildStarted = true;
            System.err.println("duel build clock landed, opening Build as "
                               + DuelIdentity.playerName()
                               + " host=" + runtime.isHost());
            DuelBuildFlow.startNetworked(pairing, runtime);
        }
    }

    /** Close a finished or broken match and leave a reason on the lobby. */
    private static void end() {
        if (runtime != null) {
            failure = runtime.link() == DuelRuntime.Link.PEER_LEFT
                ? DuelStrings.of(DuelStrings.OPPONENT_LEFT)
                : runtime.link() == DuelRuntime.Link.LOST
                    ? DuelStrings.of(DuelStrings.CONNECTION_LOST)
                    : runtime.link() == DuelRuntime.Link.DESYNC
                        ? DuelStrings.of(DuelStrings.SYNCHRONIZATION_LOST)
                        : failure;
            stopTransportKeeper();
            runtime.close();
            runtime = null;
        }
        buildStarted = false;
    }

    private static void adopt() {
        try {
            // The session is built later, when the committed Build clock
            // arrives, and the lobby is gone by then. Take the two commander
            // names off it now, while it still knows both handshakes.
            String local = DuelIdentity.playerName();
            String remote = lobby.peerHandshake() == null
                ? DuelStrings.of(DuelStrings.WAITING_FOR_OPPONENT)
                : lobby.peerHandshake().playerName;
            boolean host = lobby.state() == DuelLobby.State.PAIRED
                && lobby.agreement() != null
                && lobby.agreement().hostInstanceId
                    == lobby.localHandshake().instanceId;
            nearName = host ? local : remote;
            farName = host ? remote : local;
            runtime = lobby.adopt(DuelNetwork::sessionFor);
            startTransportKeeper(runtime);
            DuelAppLog.record("MATCH paired | local=" + local + " remote=" + remote + " host=" + host,
                DuelConnectionStatus.GOOD);
            lobby = null;
            buildStarted = false;
            failure = "";
        } catch (IOException | RuntimeException failed) {
            failure = DuelStrings.of(DuelStrings.CONNECTION_LOST);
            System.err.println("duel match transport unavailable: " + failed);
            DuelAppLog.error("Match transport", failed);
            close();
        }
    }

    /**
     * Build this device's session from the rules the two clients settled on.
     *
     * <p>Both fortresses start on the same default chassis. Nothing about the
     * opponent's fort is known here and nothing is invented for it: it stays
     * the default until their own committed {@code BUILD_COMMIT} replaces it.
     * </p>
     */
    private static DuelSession sessionFor(DuelEvent.RulesSet rules) {
        FortDesign chassis = DuelBuildFlow.defaultChassis();
        return new DuelSession(nearName, farName, chassis, chassis,
            rules.creditBudget, rules.buildSeconds, rules.matchSeconds);
    }

    /** Turn a pairing decision into something a player can read. */
    private static String describe(String reason) {
        switch (reason) {
            case "DECLINE": return DuelStrings.of(DuelStrings.DECLINED);
            case "BUSY": return DuelStrings.of(DuelStrings.BUSY);
            case "NOT_HOSTING": return DuelStrings.of(DuelStrings.NOT_HOSTING);
            case "TIMEOUT": return DuelStrings.of(DuelStrings.UNREACHABLE);
            case "PROTOCOL_MISMATCH":
                return DuelStrings.of(DuelStrings.PROTOCOL_MISMATCH);
            case "BUILD_MISMATCH":
                return DuelStrings.of(DuelStrings.BUILD_MISMATCH);
            case "ASSET_MISMATCH":
                return DuelStrings.of(DuelStrings.ASSET_MISMATCH);
            default: return DuelStrings.of(DuelStrings.INCOMPATIBLE);
        }
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Keep UDP acknowledgements and heartbeats alive across native UI stalls. */
    private static void startTransportKeeper(NetworkDuelRuntime adopted) {
        stopTransportKeeper();
        maintainedRuntime = adopted;
        transportKeeper = new Thread(() -> {
            while (maintainedRuntime == adopted) {
                try {
                    adopted.maintainTransport();
                    Thread.sleep(50L);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException failed) {
                    // The game-thread pump owns link state and the user-facing
                    // failure. Stop assisting and let its next update report
                    // the exact transport error through the existing path.
                    System.err.println("duel transport keeper stopped: "
                        + failed.getMessage());
                    return;
                }
            }
        }, "redcon-duel-udp");
        transportKeeper.setDaemon(true);
        transportKeeper.start();
    }

    private static void stopTransportKeeper() {
        maintainedRuntime = null;
        Thread keeper = transportKeeper;
        transportKeeper = null;
        if (keeper != null) {
            keeper.interrupt();
        }
    }
}
