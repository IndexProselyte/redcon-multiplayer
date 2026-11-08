package redcon.net;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Expiring, compatibility-aware model behind the native Browse screen. */
public final class DuelPeerDirectory {

    public static final long EXPIRE_MILLIS = 3500;

    public static final class Peer {
        public final InetSocketAddress address;
        public final DuelHandshake handshake;
        public final DuelHandshake.Compatibility compatibility;
        public final long lastSeenMillis;

        private Peer(InetSocketAddress address, DuelHandshake handshake,
                     DuelHandshake.Compatibility compatibility,
                     long lastSeenMillis) {
            this.address = address;
            this.handshake = handshake;
            this.compatibility = compatibility;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private final DuelHandshake local;
    private final Map<Long, Peer> peers = new HashMap<>();

    public DuelPeerDirectory(DuelHandshake local) {
        if (local == null) {
            throw new IllegalArgumentException("local handshake is null");
        }
        this.local = local;
    }

    public synchronized void observe(UdpDiscoverySocket.Announcement announcement,
                                     long nowMillis) {
        if (announcement == null) {
            throw new IllegalArgumentException("announcement is null");
        }
        DuelHandshake remote = announcement.handshake;
        if (remote.instanceId == local.instanceId) {
            return;
        }
        peers.put(remote.instanceId,
            new Peer(announcement.source, remote,
                     local.compatibilityWith(remote), nowMillis));
    }

    /** Return non-expired peers in stable name/id order. */
    public synchronized List<Peer> peers(long nowMillis) {
        expire(nowMillis);
        List<Peer> result = new ArrayList<>(peers.values());
        result.sort(Comparator
            .comparing((Peer peer) -> peer.handshake.playerName)
            .thenComparingLong(peer -> peer.handshake.instanceId));
        return result;
    }

    private void expire(long nowMillis) {
        Iterator<Peer> iterator = peers.values().iterator();
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().lastSeenMillis >= EXPIRE_MILLIS) {
                iterator.remove();
            }
        }
    }
}
