package redcon.net;

import java.util.Arrays;
import redcon.duel.DuelMatch;
import redcon.duel.FortDesign;
import redcon.duel.Order;
import redcon.duel.Side;

/**
 * Transport-free event vocabulary for a network duel.
 *
 * <p>Payloads contain values and snapshots, never live battle objects. Each
 * subtype can defensively copy itself so the in-process channel exercises the
 * ownership boundary that a packet codec will impose later.</p>
 */
public abstract class DuelEvent {

    public enum Kind {
        RULES_PROPOSE,
        RULES_SET,
        BUILD_COMMIT,
        READY_SET,
        PHASE_CLOCK,
        ORDER,
        FIRE,
        IMPACT,
        FORT_SYNC,
        DEFEATED,
        REMATCH_READY,
        LEAVE,
        ROUND_RESULT
    }

    public enum ClockPhase {
        BUILD,
        COMMENCING,
        BATTLE
    }

    public abstract Kind kind();

    /** Return a payload with no mutable storage shared with this one. */
    public abstract DuelEvent copy();

    /** Client-authored request; the host converts it to {@link RulesSet}. */
    public static final class RulesPropose extends DuelEvent {
        public final int player;
        public final int creditBudget;
        public final int buildSeconds;
        public final int matchSeconds;

        public RulesPropose(int player, int creditBudget,
                            int buildSeconds, int matchSeconds) {
            requirePlayer(player);
            if (creditBudget <= 0 || buildSeconds <= 0 || matchSeconds <= 0) {
                throw new IllegalArgumentException("duel rules must be positive");
            }
            this.player = player;
            this.creditBudget = creditBudget;
            this.buildSeconds = buildSeconds;
            this.matchSeconds = matchSeconds;
        }

        @Override public Kind kind() { return Kind.RULES_PROPOSE; }
        @Override public DuelEvent copy() {
            return new RulesPropose(player, creditBudget, buildSeconds, matchSeconds);
        }
    }

    /** Host-ordered, last-write-wins Preparation rules. */
    public static final class RulesSet extends DuelEvent {
        public final long revision;
        public final int editorPlayer;
        public final int creditBudget;
        public final int buildSeconds;
        public final int matchSeconds;

        public RulesSet(long revision, int editorPlayer, int creditBudget,
                        int buildSeconds, int matchSeconds) {
            if (revision <= 0) {
                throw new IllegalArgumentException("rules revision must be positive");
            }
            requirePlayer(editorPlayer);
            if (creditBudget <= 0 || buildSeconds <= 0 || matchSeconds <= 0) {
                throw new IllegalArgumentException("duel rules must be positive");
            }
            this.revision = revision;
            this.editorPlayer = editorPlayer;
            this.creditBudget = creditBudget;
            this.buildSeconds = buildSeconds;
            this.matchSeconds = matchSeconds;
        }

        @Override public Kind kind() { return Kind.RULES_SET; }
        @Override public DuelEvent copy() {
            return new RulesSet(revision, editorPlayer, creditBudget,
                                buildSeconds, matchSeconds);
        }
    }

    /** One player's private editor snapshot, disclosed only at commit. */
    public static final class BuildCommit extends DuelEvent {
        public final int player;
        private final FortDesign design;

        public BuildCommit(int player, FortDesign design) {
            requirePlayer(player);
            if (design == null) {
                throw new IllegalArgumentException("fort design is null");
            }
            this.player = player;
            this.design = design.copy();
        }

        public FortDesign design() { return design.copy(); }
        @Override public Kind kind() { return Kind.BUILD_COMMIT; }
        @Override public DuelEvent copy() { return new BuildCommit(player, design); }
    }

    /** Revocable Build readiness. */
    public static final class ReadySet extends DuelEvent {
        public final int player;
        public final boolean ready;

        public ReadySet(int player, boolean ready) {
            requirePlayer(player);
            this.player = player;
            this.ready = ready;
        }

        @Override public Kind kind() { return Kind.READY_SET; }
        @Override public DuelEvent copy() { return new ReadySet(player, ready); }
    }

    /** A shared monotonic interval for Build, Commencing, or Battle. */
    public static final class PhaseClock extends DuelEvent {
        public final int round;
        public final ClockPhase phase;
        public final long startTick;
        public final long deadlineTick;
        /** Shared lockstep delay; zero outside Battle. */
        public final int inputDelay;
        /** Host monotonic deadline in milliseconds; -1 for legacy/local clocks. */
        public final long hostDeadlineMillis;

        public PhaseClock(int round, ClockPhase phase,
                          long startTick, long deadlineTick) {
            this(round, phase, startTick, deadlineTick,
                 phase == ClockPhase.BATTLE ? DuelLockstep.DEFAULT_DELAY : 0,
                 -1);
        }

        public PhaseClock(int round, ClockPhase phase,
                          long startTick, long deadlineTick, int inputDelay) {
            this(round, phase, startTick, deadlineTick, inputDelay, -1);
        }

        public PhaseClock(int round, ClockPhase phase, long startTick,
                          long deadlineTick, int inputDelay,
                          long hostDeadlineMillis) {
            if (round <= 0 || phase == null || startTick < 0
                    || deadlineTick <= startTick) {
                throw new IllegalArgumentException("invalid phase clock");
            }
            if ((phase == ClockPhase.BATTLE
                    && (inputDelay < DuelLockstep.MIN_DELAY
                        || inputDelay > DuelLockstep.MAX_DELAY))
                    || (phase != ClockPhase.BATTLE && inputDelay != 0)) {
                throw new IllegalArgumentException("invalid phase input delay");
            }
            if (hostDeadlineMillis < -1) {
                throw new IllegalArgumentException("invalid host deadline");
            }
            this.round = round;
            this.phase = phase;
            this.startTick = startTick;
            this.deadlineTick = deadlineTick;
            this.inputDelay = inputDelay;
            this.hostDeadlineMillis = hostDeadlineMillis;
        }

        @Override public Kind kind() { return Kind.PHASE_CLOCK; }
        @Override public DuelEvent copy() {
            return new PhaseClock(
                round, phase, startTick, deadlineTick, inputDelay,
                hostDeadlineMillis);
        }
    }

    /** Semantic command produced by native battle input. */
    public static final class OrderIssued extends DuelEvent {
        public final int player;
        public final long tick;
        private final Order order;

        public OrderIssued(int player, long tick, Order order) {
            requirePlayer(player);
            if (tick < 0 || order == null) {
                throw new IllegalArgumentException("invalid battle order");
            }
            this.player = player;
            this.tick = tick;
            this.order = order.copy();
        }

        public Order order() { return order.copy(); }
        @Override public Kind kind() { return Kind.ORDER; }
        @Override public DuelEvent copy() { return new OrderIssued(player, tick, order); }
    }

    /** Attacker-authored shot launch; the defender resolves its outcome. */
    public static final class Fire extends DuelEvent {
        public final int attacker;
        public final long tick;
        public final long shotId;
        public final int weaponId;
        public final int ordnanceType;
        public final float aimX;
        public final float aimY;

        public Fire(int attacker, long tick, long shotId, int weaponId,
                    int ordnanceType, float aimX, float aimY) {
            requirePlayer(attacker);
            if (tick < 0 || shotId < 0 || weaponId < 0) {
                throw new IllegalArgumentException("invalid fired shot identity");
            }
            this.attacker = attacker;
            this.tick = tick;
            this.shotId = shotId;
            this.weaponId = weaponId;
            this.ordnanceType = ordnanceType;
            this.aimX = finite("aim x", aimX);
            this.aimY = finite("aim y", aimY);
        }

        @Override public Kind kind() { return Kind.FIRE; }
        @Override public DuelEvent copy() {
            return new Fire(attacker, tick, shotId, weaponId,
                            ordnanceType, aimX, aimY);
        }
    }

    /** One resolved damage effect from REDCON's direct-hit/splash records. */
    public static final class ImpactEffect {
        public final int type;
        public final float scale;
        public final float fire;
        public final float hazard;
        public final float crew;
        public final int status;
        public final int statusTicks;

        public ImpactEffect(int type, float scale, float fire, float hazard,
                            float crew, int status, int statusTicks) {
            this.type = type;
            this.scale = finite("scale", scale);
            this.fire = finite("fire", fire);
            this.hazard = finite("hazard", hazard);
            this.crew = finite("crew", crew);
            this.status = status;
            this.statusTicks = statusTicks;
        }

        public ImpactEffect copy() {
            return new ImpactEffect(type, scale, fire, hazard, crew,
                                    status, statusTicks);
        }
    }

    /** Defender-authored result of an attacking shot. */
    public static final class Impact extends DuelEvent {
        public final int defender;
        public final long tick;
        public final long shotId;
        public final int roomId;
        public final float damage;
        private final ImpactEffect[] effects;

        public Impact(int defender, long tick, long shotId, int roomId,
                      float damage, ImpactEffect[] effects) {
            requirePlayer(defender);
            if (tick < 0 || shotId < 0 || roomId < 0) {
                throw new IllegalArgumentException("invalid impact identity");
            }
            this.defender = defender;
            this.tick = tick;
            this.shotId = shotId;
            this.roomId = roomId;
            this.damage = finite("damage", damage);
            this.effects = copyEffects(effects);
        }

        public ImpactEffect[] effects() { return copyEffects(effects); }
        @Override public Kind kind() { return Kind.IMPACT; }
        @Override public DuelEvent copy() {
            return new Impact(defender, tick, shotId, roomId, damage, effects);
        }
    }

    /** Defender-authored drift correction for all included rooms. */
    public static final class FortSync extends DuelEvent {
        public final int player;
        public final long tick;
        private final int[] roomIds;
        private final float[] hitPoints;
        private final int[] states;

        public FortSync(int player, long tick, int[] roomIds,
                        float[] hitPoints, int[] states) {
            requirePlayer(player);
            if (tick < 0 || roomIds == null || hitPoints == null || states == null
                    || roomIds.length != hitPoints.length
                    || roomIds.length != states.length) {
                throw new IllegalArgumentException("invalid fort correction arrays");
            }
            this.player = player;
            this.tick = tick;
            this.roomIds = roomIds.clone();
            this.hitPoints = hitPoints.clone();
            this.states = states.clone();
            for (int i = 0; i < this.roomIds.length; i++) {
                if (this.roomIds[i] < 0) {
                    throw new IllegalArgumentException("negative room id");
                }
                finite("room hit points", this.hitPoints[i]);
            }
        }

        public int size() { return roomIds.length; }
        public int roomId(int index) { return roomIds[index]; }
        public float hitPoints(int index) { return hitPoints[index]; }
        public int state(int index) { return states[index]; }
        public int[] roomIds() { return roomIds.clone(); }
        public float[] hitPoints() { return hitPoints.clone(); }
        public int[] states() { return states.clone(); }
        @Override public Kind kind() { return Kind.FORT_SYNC; }
        @Override public DuelEvent copy() {
            return new FortSync(player, tick, roomIds, hitPoints, states);
        }

        @Override
        public String toString() {
            return "FORT_SYNC player=" + player + " tick=" + tick
                 + " rooms=" + Arrays.toString(roomIds);
        }
    }

    /** A client declares the defeat of its own fortress. */
    public static final class Defeated extends DuelEvent {
        public final int player;
        public final long tick;

        public Defeated(int player, long tick) {
            requirePlayer(player);
            if (tick < 0) {
                throw new IllegalArgumentException("negative defeat tick");
            }
            this.player = player;
            this.tick = tick;
        }

        @Override public Kind kind() { return Kind.DEFEATED; }
        @Override public DuelEvent copy() { return new Defeated(player, tick); }
    }

    /** Host-committed exact round outcome after defeat or the Battle deadline. */
    public static final class RoundResult extends DuelEvent {
        /** {@link Side#NEAR}, {@link Side#FAR}, or -1 for a draw. */
        public final int winner;
        public final DuelMatch.Ending ending;
        public final float seconds;

        public RoundResult(int winner, DuelMatch.Ending ending, float seconds) {
            if (winner != Side.NEAR && winner != Side.FAR && winner != -1) {
                throw new IllegalArgumentException("bad result winner " + winner);
            }
            if (ending == null || !Float.isFinite(seconds) || seconds < 0.0f) {
                throw new IllegalArgumentException("invalid round result");
            }
            if ((ending == DuelMatch.Ending.SIMULTANEOUS
                    || ending == DuelMatch.Ending.CLOCK_DRAW) != (winner == -1)) {
                throw new IllegalArgumentException(
                    "round ending and winner disagree");
            }
            this.winner = winner;
            this.ending = ending;
            this.seconds = seconds;
        }

        @Override public Kind kind() { return Kind.ROUND_RESULT; }
        @Override public DuelEvent copy() {
            return new RoundResult(winner, ending, seconds);
        }
    }

    public static final class RematchReady extends DuelEvent {
        public final int player;
        public final boolean ready;

        public RematchReady(int player, boolean ready) {
            requirePlayer(player);
            this.player = player;
            this.ready = ready;
        }

        @Override public Kind kind() { return Kind.REMATCH_READY; }
        @Override public DuelEvent copy() { return new RematchReady(player, ready); }
    }

    public static final class Leave extends DuelEvent {
        public final int player;
        public final String reason;

        public Leave(int player, String reason) {
            requirePlayer(player);
            this.player = player;
            this.reason = reason == null ? "" : reason;
        }

        @Override public Kind kind() { return Kind.LEAVE; }
        @Override public DuelEvent copy() { return new Leave(player, reason); }
    }

    private static ImpactEffect[] copyEffects(ImpactEffect[] source) {
        if (source == null) {
            return new ImpactEffect[0];
        }
        ImpactEffect[] result = new ImpactEffect[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] == null) {
                throw new IllegalArgumentException("null impact effect");
            }
            result[i] = source[i].copy();
        }
        return result;
    }

    private static float finite(String name, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " is not finite");
        }
        return value;
    }

    static void requirePlayer(int player) {
        if (player != Side.NEAR && player != Side.FAR) {
            throw new IllegalArgumentException("bad player " + player);
        }
    }
}
