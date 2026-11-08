package redcon.net;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-side, transport-independent Pair Request decision state machine. */
public final class DuelPairingCoordinator {

    private static final int MAX_CACHED_RESULTS = 64;

    public static final class Result {
        public final DuelPairingMessage.Response response;
        public final DuelPairingMessage.Ok ok;

        private Result(DuelPairingMessage.Response response,
                       DuelPairingMessage.Ok ok) {
            this.response = response;
            this.ok = ok;
        }
    }

    private final DuelHandshake host;
    private static final class CachedResult {
        final byte[] requesterHandshake;
        final Result result;

        CachedResult(DuelHandshake requester, Result result) {
            this.requesterHandshake = requester.encode();
            this.result = result;
        }
    }

    private final Map<Long, CachedResult> decided = new LinkedHashMap<>();
    private Long acceptedRequestId;
    private boolean accepting = true;
    private boolean paired;

    public DuelPairingCoordinator(DuelHandshake host) {
        if (host == null) {
            throw new IllegalArgumentException("host handshake is null");
        }
        this.host = host;
        this.paired = host.state == DuelHandshake.State.BUSY;
    }

    /**
     * Resolve a request. Generated ids are supplied by the platform's secure
     * random source; passing them in keeps the state machine reproducible.
     */
    public synchronized Result handle(DuelPairingMessage.Request request,
                                      long matchId, long transportSessionId,
                                      long sessionSeed, long rulesRevision) {
        if (request == null) {
            throw new IllegalArgumentException("pair request is null");
        }
        if (request.targetInstanceId != host.instanceId) {
            throw new IllegalArgumentException("pair request targets another host");
        }
        CachedResult previous = decided.get(request.requestId);
        if (previous != null) {
            if (!Arrays.equals(previous.requesterHandshake,
                               request.requester.encode())) {
                throw new IllegalArgumentException(
                    "pair request id was reused by another request");
            }
            return previous.result;
        }

        DuelHandshake.Compatibility compatibility =
            host.compatibilityWith(request.requester);
        DuelPairingMessage.Decision decision;
        DuelPairingMessage.Ok ok = null;
        if (compatibility != DuelHandshake.Compatibility.COMPATIBLE) {
            decision = DuelPairingMessage.Decision.INCOMPATIBLE;
        } else if (paired) {
            decision = DuelPairingMessage.Decision.BUSY;
        } else if (!accepting) {
            decision = DuelPairingMessage.Decision.DECLINE;
        } else {
            decision = DuelPairingMessage.Decision.ACCEPT;
            ok = new DuelPairingMessage.Ok(
                request.requestId, host.instanceId, request.requester.instanceId,
                matchId, transportSessionId, sessionSeed, rulesRevision);
            paired = true;
        }
        DuelPairingMessage.Response response = new DuelPairingMessage.Response(
            request.requestId, host.instanceId, decision, compatibility);
        Result result = new Result(response, ok);
        cache(request, result);
        return result;
    }

    private void cache(DuelPairingMessage.Request request, Result result) {
        decided.put(request.requestId,
                    new CachedResult(request.requester, result));
        if (result.ok != null) {
            acceptedRequestId = request.requestId;
        }
        while (decided.size() > MAX_CACHED_RESULTS) {
            Iterator<Long> ids = decided.keySet().iterator();
            while (ids.hasNext()) {
                Long id = ids.next();
                if (!id.equals(acceptedRequestId)) {
                    ids.remove();
                    break;
                }
            }
        }
    }

    public synchronized void setAccepting(boolean accepting) {
        this.accepting = accepting;
    }

    public synchronized boolean paired() { return paired; }
}
