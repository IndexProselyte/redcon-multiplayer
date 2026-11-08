package redcon.net;

/** Value messages exchanged before a committed match event stream exists. */
public abstract class DuelPairingMessage {

    public enum Kind { REQUEST, RESPONSE, OK }

    public enum Decision { ACCEPT, DECLINE, BUSY, INCOMPATIBLE }

    public abstract Kind kind();

    public static final class Request extends DuelPairingMessage {
        public final long requestId;
        public final long targetInstanceId;
        public final DuelHandshake requester;

        public Request(long requestId, long targetInstanceId,
                       DuelHandshake requester) {
            if (requestId == 0 || targetInstanceId == 0 || requester == null) {
                throw new IllegalArgumentException("invalid pair request");
            }
            this.requestId = requestId;
            this.targetInstanceId = targetInstanceId;
            this.requester = requester;
        }

        @Override public Kind kind() { return Kind.REQUEST; }
    }

    public static final class Response extends DuelPairingMessage {
        public final long requestId;
        public final long hostInstanceId;
        public final Decision decision;
        public final DuelHandshake.Compatibility compatibility;

        public Response(long requestId, long hostInstanceId, Decision decision,
                        DuelHandshake.Compatibility compatibility) {
            if (requestId == 0 || hostInstanceId == 0 || decision == null
                    || compatibility == null) {
                throw new IllegalArgumentException("invalid pair response");
            }
            if ((decision == Decision.INCOMPATIBLE)
                    != (compatibility != DuelHandshake.Compatibility.COMPATIBLE)) {
                throw new IllegalArgumentException(
                    "pair decision and compatibility disagree");
            }
            this.requestId = requestId;
            this.hostInstanceId = hostInstanceId;
            this.decision = decision;
            this.compatibility = compatibility;
        }

        @Override public Kind kind() { return Kind.RESPONSE; }
    }

    public static final class Ok extends DuelPairingMessage {
        public final long requestId;
        public final long hostInstanceId;
        public final long guestInstanceId;
        public final long matchId;
        public final long transportSessionId;
        public final long sessionSeed;
        public final long rulesRevision;

        public Ok(long requestId, long hostInstanceId, long guestInstanceId,
                  long matchId, long transportSessionId, long sessionSeed,
                  long rulesRevision) {
            if (requestId == 0 || hostInstanceId == 0 || guestInstanceId == 0
                    || matchId == 0 || transportSessionId == 0
                    || rulesRevision < 0) {
                throw new IllegalArgumentException("invalid pair OK");
            }
            this.requestId = requestId;
            this.hostInstanceId = hostInstanceId;
            this.guestInstanceId = guestInstanceId;
            this.matchId = matchId;
            this.transportSessionId = transportSessionId;
            this.sessionSeed = sessionSeed;
            this.rulesRevision = rulesRevision;
        }

        @Override public Kind kind() { return Kind.OK; }
    }
}
