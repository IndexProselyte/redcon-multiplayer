package redcon.desktop;

import java.util.Collections;
import java.util.List;
import redcon.net.DuelHandshake;
import redcon.net.DuelLobby;
import redcon.net.DuelPeerDirectory;

/**
 * The first multiplayer screen: which duel to join.
 *
 * <p>Built from REDCON's sub-screen grammar - dimmed scene, the shell's own
 * top-left Back, a centred list - so it reads as a descendant of the title
 * menu rather than a server browser. The rows are now real: one line per
 * device announcing itself on this network, with a peer whose protocol, build
 * or content differs listed and named rather than hidden.</p>
 *
 * <p>The list is rebuilt through REDCON's own {@code a0()} whenever the set of
 * peers or the lobby's state changes, which is at most once a second - that is
 * how often a device announces. Nothing rebuilds on a quiet frame.</p>
 */
public final class DuelBrowseActivity extends f3.e {

    public static final int EVENT_LOCAL_DUEL = 92;
    public static final int EVENT_BACK = 93;
    public static final int EVENT_HOST = 106;
    public static final int EVENT_CANCEL_LOBBY = 107;
    public static final int EVENT_PREVIOUS_PAGE = 108;
    public static final int EVENT_NEXT_PAGE = 109;
    public static final int EVENT_NAME = 125;

    /** One event per listed peer, in the order {@link #peers} holds them. */
    public static final int EVENT_PEER_FIRST = 110;
    private static final int MAX_LISTED_PEERS = 8;

    private List<DuelPeerDirectory.Peer> peers =
        Collections.<DuelPeerDirectory.Peer>emptyList();
    private String signature = "";
    private final DuelConnectionStatus connection = new DuelConnectionStatus();
    private final DuelConnectionPanel panel = new DuelConnectionPanel(connection);
    private i3.b peerList;
    private int page;

    boolean scrollLog(int x, int y, float amount) {
        if (!panel.d(x, y)) return false;
        panel.scroll(amount > 0 ? 3 : -3);
        return true;
    }

    private boolean wide() { return mojo.b.o >= 1100; }
    private int pageSize() {
        int reserved = (wide() ? 374 : 564) + (DuelDevRoute.enabled() ? 140 : 0);
        return Math.max(1, Math.min(MAX_LISTED_PEERS, (mojo.b.p - reserved) / 64));
    }

    @Override
    public void a0() {
        // f3.e.R() dims the scene instead of drawing the sky when this is set,
        // which is what marks a sub-screen in the original game.
        k = true;
        DuelNetwork.open();
        peers = new java.util.ArrayList<>(DuelNetwork.peers());
        peers.sort(java.util.Comparator.comparingInt((DuelPeerDirectory.Peer peer) ->
            peer.compatibility == DuelHandshake.Compatibility.COMPATIBLE
                && peer.handshake.state == DuelHandshake.State.IDLE ? 0 : 1)
            .thenComparing(peer -> peer.handshake.playerName));
        page = Math.min(page, Math.max(0, (peers.size() - 1) / pageSize()));
        observeConnection();
        signature = signatureOf();
        super.a0();
    }

    @Override
    public void G(i3.j root) {
        H(root);
        panel.s();
        root.x(panel);
    }

    @Override public void h() {
        super.h();
        if (peerList == null) return;
        if (wide()) {
            int rightWidth = Math.min(420, mojo.b.o - 669);
            int left = (mojo.b.o - 573 - 32 - rightWidth) / 2;
            peerList.u(left, 100, 573, mojo.b.p - 140);
            panel.u(left + 605, 130, rightWidth, Math.min(320, mojo.b.p - 170));
        } else {
            peerList.u(Math.max(0, (mojo.b.o - 573) / 2), 74, 573, mojo.b.p - 326);
            panel.u(32, mojo.b.p - 238, mojo.b.o - 64, 214);
        }
    }

    @Override
    public void I(i3.j root, i3.b list) {
        peerList = list;
        DuelMenu.column(list);
        DuelMenu.heading(list, DuelStrings.of(DuelStrings.NETWORK));

        DuelMenu.row(list, this, EVENT_NAME, "NAME", DuelIdentity.playerName(), -1);

        DuelLobby lobby = DuelNetwork.lobby();
        if (lobby == null) {
            DuelMenu.note(list, failureOrDefault());
        } else {
            listPeers(list, lobby);
        }

        if (DuelDevRoute.enabled()) {
            // Not a feature: a stand-in so the duel loop can be played and
            // regression-tested before there is a peer to play. Absent from a
            // normal build, which offers only the list above.
            DuelMenu.heading(list, DuelStrings.of(DuelStrings.DEVELOPMENT));
            E(DuelMenu.row(list, this, EVENT_LOCAL_DUEL,
                           DuelStrings.of(DuelStrings.LOCAL_STAND_IN), null, -1));
        }
    }

    private void listPeers(i3.b list, DuelLobby lobby) {
        switch (lobby.state()) {
            case HOSTING:
                DuelMenu.note(list,
                    DuelStrings.of(DuelStrings.WAITING_FOR_PLAYER));
                E(DuelMenu.row(list, this, EVENT_CANCEL_LOBBY,
                    DuelStrings.of(DuelStrings.LEAVE_DUEL), null, -1));
                return;
            case JOINING:
                DuelMenu.note(list, DuelStrings.of(DuelStrings.JOINING));
                E(DuelMenu.row(list, this, EVENT_CANCEL_LOBBY,
                    DuelStrings.of(DuelStrings.LEAVE_DUEL), null, -1));
                return;
            default:
                break;
        }

        E(DuelMenu.row(list, this, EVENT_HOST,
            DuelStrings.of(DuelStrings.HOST_GAME), null, -1));
        if (peers.isEmpty()) {
            DuelMenu.note(list, DuelStrings.of(DuelStrings.SEARCHING));
        }
        int first = page * pageSize();
        int shown = Math.min(peers.size(), first + pageSize());
        for (int index = first; index < shown; index++) {
            DuelPeerDirectory.Peer peer = peers.get(index);
            boolean joinable = peer.compatibility
                    == DuelHandshake.Compatibility.COMPATIBLE
                && peer.handshake.state == DuelHandshake.State.IDLE;
            i3.b row = DuelMenu.row(list, this,
                joinable ? EVENT_PEER_FIRST + index - first : 0,
                DuelUiText.fit(h3.c.o, peer.handshake.playerName, 310),
                DuelUiText.fit(h3.c.X, statusOf(peer), 235),
                joinable ? DuelConnectionStatus.GOOD : DuelMenu.DISABLED);
            if (joinable) {
                E(row);
            }
        }
        if (peers.size() > pageSize()) {
            DuelMenu.choice(list, this, EVENT_PREVIOUS_PAGE, EVENT_NEXT_PAGE,
                DuelStrings.of(DuelStrings.DEVICES), (page + 1) + "/" + ((peers.size() - 1) / pageSize() + 1));
        }
        if (!DuelNetwork.failure().isEmpty()) {
            DuelMenu.note(list, DuelNetwork.failure());
        }
    }

    @Override
    public void X(int event, Object data) {
        DuelLobby lobby = DuelNetwork.lobby();
        if (event == EVENT_NAME && (lobby == null || lobby.state() == DuelLobby.State.BROWSING
                || lobby.state() == DuelLobby.State.FAILED)) {
            DuelNetwork.close();
            DuelNameActivity prompt = new DuelNameActivity(this);
            prompt.d = d;
            mojo.b.i.g(prompt);
        } else if (event == EVENT_LOCAL_DUEL) {
            // Taking the stand-in means this device is not playing the
            // network, so the lobby goes with it. Left open, a peer that
            // announced itself while the stand-in duel was being set up would
            // pair underneath it and turn a local session into a networked
            // one - which is what made the rules rows on Pairing revert to a
            // committed revision nobody on this machine had chosen.
            DuelNetwork.close();
            mojo.b.i.d(new DuelPairingActivity());
        } else if (event == EVENT_HOST && lobby != null) {
            if (lobby.state() == DuelLobby.State.BROWSING || lobby.state() == DuelLobby.State.FAILED) lobby.host();
            rebuild();
        } else if (event == EVENT_CANCEL_LOBBY && lobby != null) {
            if (lobby.state() != DuelLobby.State.PAIRED) lobby.cancel();
            rebuild();
        } else if (event == EVENT_PREVIOUS_PAGE || event == EVENT_NEXT_PAGE) {
            int pages = Math.max(1, (peers.size() - 1) / pageSize() + 1);
            page = Math.floorMod(page + (event == EVENT_NEXT_PAGE ? 1 : -1), pages);
            rebuild();
        } else if (event >= EVENT_PEER_FIRST
                && event < EVENT_PEER_FIRST + MAX_LISTED_PEERS
                && lobby != null) {
            int index = page * pageSize() + event - EVENT_PEER_FIRST;
            if (index < peers.size() && (lobby.state() == DuelLobby.State.BROWSING
                    || lobby.state() == DuelLobby.State.FAILED)) {
                lobby.join(peers.get(index));
                rebuild();
            }
        } else if (event == EVENT_BACK || event == 1) {
            j();
        }
    }

    /**
     * Follow the lobby from the frame pump.
     *
     * <p>Called once a frame while this screen is up. It rebuilds only when
     * the visible facts have changed, and hands over to Pairing the moment
     * the lobby has become a match - which happens on the host's device
     * without anybody touching it.</p>
     */
    static void refreshActive() {
        if (!(mojo.b.i.b instanceof DuelBrowseActivity)) {
            return;
        }
        DuelBrowseActivity screen = (DuelBrowseActivity) mojo.b.i.b;
        screen.observeConnection();
        if (DuelNetwork.runtime() != null) {
            mojo.b.i.d(new DuelPairingActivity());
            return;
        }
        if (!screen.signatureOf().equals(screen.signature)) {
            screen.rebuild();
        }
    }

    /** Rebuild through REDCON's own screen construction, then lay it out. */
    private void rebuild() {
        a0();
        h();
    }

    private void observeConnection() {
        DuelLobby lobby = DuelNetwork.lobby();
        java.util.Map<Long, String> observed = new java.util.LinkedHashMap<>();
        for (DuelPeerDirectory.Peer peer : DuelNetwork.peers()) {
            observed.put(peer.handshake.instanceId, peer.handshake.playerName + " - " + statusOf(peer));
        }
        connection.observe(lobby == null ? null : lobby.state(), DuelNetwork.failure(),
            lobby == null ? 0 : lobby.pairingAttempts(), lobby != null && lobby.hostAccepted(), observed);
    }

    /** Everything the list shows, so a quiet frame changes nothing. */
    private String signatureOf() {
        DuelLobby lobby = DuelNetwork.lobby();
        StringBuilder text = new StringBuilder();
        text.append(lobby == null ? "closed" : lobby.state().name());
        text.append('|').append(DuelNetwork.failure());
        for (DuelPeerDirectory.Peer peer : DuelNetwork.peers()) {
            text.append('|').append(peer.handshake.instanceId)
                .append('/').append(peer.handshake.playerName)
                .append('/').append(peer.handshake.state)
                .append('/').append(peer.compatibility);
        }
        return text.toString();
    }

    private String statusOf(DuelPeerDirectory.Peer peer) {
        switch (peer.compatibility) {
            case PROTOCOL_MISMATCH:
                return DuelStrings.of(DuelStrings.PROTOCOL_MISMATCH);
            case BUILD_MISMATCH:
                return DuelStrings.of(DuelStrings.BUILD_MISMATCH);
            case ASSET_MISMATCH:
                return DuelStrings.of(DuelStrings.ASSET_MISMATCH);
            default:
                return peer.handshake.state == DuelHandshake.State.BUSY
                    ? DuelStrings.of(DuelStrings.BUSY)
                    : peer.handshake.state == DuelHandshake.State.BROWSING
                        ? DuelStrings.of(DuelStrings.NOT_HOSTING)
                        : DuelStrings.of(DuelStrings.HOST_OPEN);
        }
    }

    private String failureOrDefault() {
        String failure = DuelNetwork.failure();
        return failure.isEmpty()
            ? DuelStrings.of(DuelStrings.NO_GAMES) : failure;
    }

    @Override
    public void j() {
        DuelNetwork.close();
        if (d instanceof AcademyMapActivity) {
            // Browse now sits behind the Academy marker rather than behind the
            // title menu, so Back from here returns to a duel place, not to
            // the campaign. It keeps duel mode and hands the map its borrowed
            // state back, exactly as Pairing's own return does.
            ((AcademyMapActivity) d).prepareForReturn();
        } else {
            GameContext.enterCampaign();
        }
        super.j();
    }
}
