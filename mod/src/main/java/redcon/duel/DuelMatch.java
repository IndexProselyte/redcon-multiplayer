package redcon.duel;

import c1.a;
import o3.a1;
import o3.c1;
import o3.z0;
import o3.v1;
import redcon.desktop.Duel;

/**
 * A duel: two sides, two commanders, and a rule for when it is over.
 *
 * <h2>The battle stays where it is</h2>
 *
 * This does not replace {@code o3.a1}. The battle is still the engine's, armed
 * by {@link Duel#arm}, and the simulation inside it is untouched - which is what
 * the duel spec asks for, since combat is explicitly unchanged. What is added is
 * the layer above: who is in charge of each fortress, and what ends the match.
 *
 * <h2>The engine already decides defeat, and it does it both ways</h2>
 *
 * {@code a1.P(attacker, defender, objectiveMask)} returns -1 while the defender
 * is still in the fight and an outcome code once it is not. It is not a
 * player-specific check - it takes both fortresses as arguments - and
 * {@code a1.C} already calls it twice per frame, once in each direction:
 * {@code P(U0, V0, missionObjective)} to see whether the player has won and
 * {@code P(V0, U0, -1)} to see whether they have lost.
 *
 * <p>So a duel does not need a hand-written win condition. It needs the call the
 * engine already makes, with the same mask in both directions. Mask 2 is the
 * one that matches the spec's definition, because inside {@code P} it selects
 * exactly these branches:
 *
 * <ul>
 *   <li>the defender has no functioning weapon ({@code X} is empty),</li>
 *   <li>the defender has no living crew ({@code d0} is empty),</li>
 *   <li>the defender's hull has reached zero.</li>
 * </ul>
 *
 * <p>which is "no functioning weapon, or no living crew", plus the obvious
 * third case. Simultaneous defeat in the same frame is a draw, as specified.
 *
 * <h2>Two things copied from the engine's own discipline</h2>
 *
 * {@code a1.C} requires the same outcome on <b>30 consecutive frames</b> before
 * acting on it, which stops a fortress that is momentarily weaponless - mid
 * reload, mid repair - from losing on a flicker. That debounce is kept here.
 * And the match clock is the spec's stalemate rule rather than the engine's:
 * REDCON's campaign has no round timer because its missions have objectives.
 */
public final class DuelMatch {

    /**
     * The objective mask for a duel.
     *
     * Bit 1 is what {@code a1.P} calls {@code z4}, and it is the bit that turns
     * on the "no weapons left" and "no crew left" branches. Bit 0 is a
     * campaign objective about a specific structure surviving, and is
     * deliberately not set.
     */
    public static final int DUEL_OBJECTIVE = 2;

    /** Frames an outcome has to hold before it counts. The engine uses 30. */
    private static final int CONFIRM_FRAMES = 30;

    /** Ten minutes, the spec's first guess at a stalemate cap. */
    public static final int DEFAULT_MATCH_SECONDS = 600;

    /** Why a duel ended. */
    public enum Ending {
        /** A fortress met a losing condition. */
        DEFEAT,
        /** Both did, in the same frame. */
        SIMULTANEOUS,
        /** The match clock ran out; the win went on damage dealt. */
        CLOCK_DAMAGE,
        /** The clock ran out with damage level; the win went on surviving rooms. */
        CLOCK_ROOMS,
        /** The clock ran out and nothing separated them. */
        CLOCK_DRAW
    }

    /** How a duel finished. */
    public static final class Result {
        /** The winner, or null for a draw. */
        public final Side winner;
        public final Ending ending;
        public final float seconds;

        Result(Side winner, Ending ending, float seconds) {
            this.winner = winner;
            this.ending = ending;
            this.seconds = seconds;
        }

        public boolean isDraw() {
            return winner == null;
        }

        @Override
        public String toString() {
            return (winner == null ? "draw" : winner.name + " wins")
                   + " by " + ending + " at " + String.format("%.2fs", seconds);
        }
    }

    private final a1 battle;
    private final Side near;
    private final Side far;
    private final Commander nearCommander;
    private final Commander farCommander;
    private final int matchLimitTicks;

    private Result result;
    private int nearLosingStreak;
    private int farLosingStreak;
    private int frames;

    private DuelMatch(a1 battle, Side near, Side far,
                      Commander nearCommander, Commander farCommander,
                      int matchSeconds) {
        this.battle = battle;
        this.near = near;
        this.far = far;
        this.nearCommander = nearCommander;
        this.farCommander = farCommander;
        this.matchLimitTicks = matchSeconds * Duel.TICKS_PER_SECOND;
    }

    /**
     * Arm a duel between two blueprints, with a commander in charge of each.
     *
     * Either commander may be human or AI, in any combination: two humans is a
     * duel, one of each is practice, and two AIs is the Phase 02 harness.
     */
    public static DuelMatch arm(z0 nearFort, z0 farFort,
                                Commander nearCommander, Commander farCommander)
            throws Exception {
        return arm(nearFort, farFort, nearCommander, farCommander, DEFAULT_MATCH_SECONDS);
    }

    public static DuelMatch arm(z0 nearFort, z0 farFort,
                                Commander nearCommander, Commander farCommander,
                                int matchSeconds) throws Exception {
        return adopt(Duel.arm(nearFort, farFort),
                     nearCommander, farCommander, matchSeconds);
    }

    /**
     * Take charge of a battle somebody else already armed.
     *
     * {@link #arm} is the headless route, and it arms the battle itself through
     * {@link Duel}. A battle that is going to be <em>drawn</em> has to be armed
     * the game's own way instead - {@code q3.b.K}, the battle screen's opener,
     * builds the whole widget tree and only then calls {@code a1.A0} to arm the
     * fight, and {@code a1.q()} paints those widgets. Arming by hand leaves them
     * null and the paint dies on the first one.
     *
     * <p>So the windowed duel lets the screen arm the battle and hands the
     * result here. The caller owns two things this cannot check: that the
     * battle is armed, and that its two loadouts carry {@link Duel#TEAM_NEAR}
     * and {@link Duel#TEAM_FAR}, since {@code A0} copies the team id out of the
     * loadout and a screen-built mission has no reason to have set it.
     */
    public static DuelMatch adopt(a1 battle,
                                  Commander nearCommander, Commander farCommander) {
        return adopt(battle, nearCommander, farCommander, DEFAULT_MATCH_SECONDS);
    }

    public static DuelMatch adopt(a1 battle,
                                  Commander nearCommander, Commander farCommander,
                                  int matchSeconds) {
        // A screen-armed battle can come up with mojo.b.u at zero, and then the
        // clock never moves and nothing is ever ticked. Duel.arm sets it; this
        // path has to as well.
        Duel.ensureFramePacing();

        // c1.E resets the spatial generation counter to -1 but leaves its
        // buckets stamped with previous-round generations. A bucket not yet
        // touched in this round becomes valid again when that frame number
        // recurs, exposing retired projectiles to defense and collision queries.
        java.util.Arrays.fill(battle.k.g, null);

        Side near = new Side(Side.NEAR, nearCommander.name(), a1.U0);
        Side far = new Side(Side.FAR, farCommander.name(), a1.V0);
        near.pairWith(far);
        far.pairWith(near);

        nearCommander.bind(near);
        farCommander.bind(far);

        DuelMatch match =
            new DuelMatch(battle, near, far, nearCommander, farCommander, matchSeconds);
        // The near side holds the seat by default, because the engine's HUD
        // reads a single selection and something has to own it.
        near.takeSeat();
        clearPreviousRoundResidue(near);
        clearPreviousRoundResidue(far);
        // Before anything is drawn: the committed seed is the state the first
        // simulation frame has to resume from.
        match.bankGenerator();
        return match;
    }

    /**
     * Reset firing state retained by pooled rooms between battles. The native
     * g0 initializer resets weapon state only when the new room has firing
     * modes; a former weapon reused as a utility room keeps its old modes,
     * target, reload and burst fields unless we clear them here. Weapon accuracy
     * g2 is already seeded by g0, so preserve it for actual weapons.
     */
    private static void clearPreviousRoundResidue(Side side) {
        for (int index = 0; index < side.fortress.Q; index++) {
            v1 room = side.fortress.R[index];
            if (room == null) {
                continue;
            }
            room.Z1 = 0;
            room.a2 = 0;
            room.f2 = 0;
            // Cumulative damage selects the damaged structural variant once
            // it crosses half the room's health; native d0 resets HP but not j1.
            room.j1 = 0.0f;
            if (room.h2 == null) {
                room.L0 = null;
                room.V1 = 0;
                room.W1 = false;
                room.X1 = false;
                room.Y1 = 0;
                room.d2 = null;
                room.e2 = null;
                room.b2 = 0.0f;
                room.c2 = 0.0f;
                room.g2 = 0;
            }
            if (room.i2 != null) {
                java.util.Arrays.fill(room.i2, 0.0f);
            }
        }
    }

    // REDCON's generators as this match's simulation last left them. The
    // 48-bit stream a.N draws from, the multiply-with-carry pair behind
    // a.F, and the shuffle state the two share.
    private long bankedLcg;
    private int bankedT;
    private int bankedU;
    private int bankedP;
    private int bankedQ;
    private int bankedR;
    private int[] bankedS;

    /**
     * Put the simulation's own generator state back before stepping it.
     *
     * <p>REDCON draws its randomness from statics on {@code c1.a}, and the
     * game that shipped had no reason to separate the fight from the show: a
     * muzzle flash, a puff of smoke, a shell casing and a crew animation all
     * draw from the same stream the simulation does. Single-player, that is
     * fine. Two devices agreeing on a fight frame by frame, it is not - the
     * presentation runs at whatever rate the device draws at, sixty a second
     * on a desktop and thirty on this handset, so by four seconds in the two
     * streams were hundreds of draws apart and the next shell fired went
     * somewhere else.</p>
     *
     * <p>So the simulation's stream is fenced rather than hunted: whatever the
     * presentation draws between two frames, the next frame resumes from the
     * state the previous frame ended on. Presentation keeps its variety,
     * because each frame hands it a state it has not seen before; it simply
     * cannot advance the next frame's generator. This only fences draws made
     * between simulation calls. Device-dependent effects inside J(), and audio
     * callbacks concurrent with it, require their own independent RNG domains;
     * banking this global alone cannot isolate those paths.</p>
     */
    private void restoreGenerator() {
        if (bankedS == null) {
            return;
        }
        a.j = bankedLcg;
        a.t = bankedT;
        a.u = bankedU;
        a.p = bankedP;
        a.q = bankedQ;
        a.r = bankedR;
        if (a.s != null) {
            System.arraycopy(bankedS, 0, a.s, 0,
                Math.min(bankedS.length, a.s.length));
        }
    }

    /** Bank what the simulation drew, so presentation cannot spend it. */
    private void bankGenerator() {
        bankedLcg = a.j;
        bankedT = a.t;
        bankedU = a.u;
        bankedP = a.p;
        bankedQ = a.q;
        bankedR = a.r;
        bankedS = a.s == null ? new int[0] : a.s.clone();
    }

    /** Committed inputs share the simulation RNG, even before entity stepping. */
    public boolean applyOrder(Side side, Order order) {
        if (side != near && side != far) throw new IllegalArgumentException("foreign side");
        a1.S0 = false;
        a1.T0 = 1;
        restoreGenerator();
        boolean accepted = order.apply(side);
        near.refreshCrewIds();
        far.refreshCrewIds();
        bankGenerator();
        return accepted;
    }

    /**
     * One update-only frame: entities, then both commanders, then the clock.
     *
     * This is {@code a1.C} with its presentation tail left off, which is the
     * same trade {@link Duel#frame} makes and for the same reason - {@code C()}
     * ends with the actor list and two looping audio cues on every frame,
     * whatever the battle state. The difference here is that each side's
     * commander is asked to act rather than {@code X0} alone.
     */
    public void frame() {
        // Duel mode has no pause and no speed control. Enforce that rule at
        // the simulation authority rather than trying to find every campaign
        // widget, hotkey, focus callback, or popup that may write these globals.
        // Once result is non-null the owner stops calling frame(), which keeps
        // the legitimate post-battle pause.
        a1.S0 = false;
        a1.T0 = 1;
        // The simulation resumes the generator exactly where its own last
        // frame left it. See restoreGenerator for why that is not the same as
        // leaving it alone.
        restoreGenerator();
        battle.J();
        nearCommander.update();
        farCommander.update();
        battle.p();
        near.refreshCrewIds();
        far.refreshCrewIds();
        bankGenerator();
        frames++;

        near.sampleDamage();
        far.sampleDamage();

        if (result == null) {
            result = evaluate();
        }
    }

    /**
     * Ask the engine whether either side has lost, and apply the clock.
     *
     * @return the result, or null while the duel is still live.
     */
    private Result evaluate() {
        // P(x, y, mask) reports on y. Called once per direction, the same way
        // a1.C calls it, but with the duel mask on both sides rather than the
        // mission objective on one and everything on the other.
        boolean farLost = a1.P(near.fortress, far.fortress, DUEL_OBJECTIVE) != -1;
        boolean nearLost = a1.P(far.fortress, near.fortress, DUEL_OBJECTIVE) != -1;

        farLosingStreak = farLost ? farLosingStreak + 1 : 0;
        nearLosingStreak = nearLost ? nearLosingStreak + 1 : 0;

        boolean farConfirmed = farLosingStreak >= CONFIRM_FRAMES;
        boolean nearConfirmed = nearLosingStreak >= CONFIRM_FRAMES;

        if (farConfirmed && nearConfirmed) {
            return new Result(null, Ending.SIMULTANEOUS, elapsedSeconds());
        }
        if (farConfirmed) {
            return new Result(near, Ending.DEFEAT, elapsedSeconds());
        }
        if (nearConfirmed) {
            return new Result(far, Ending.DEFEAT, elapsedSeconds());
        }
        if (c1.o >= matchLimitTicks) {
            return onTheClock();
        }
        return null;
    }

    /** The spec's tiebreak: damage dealt, then surviving rooms, then a draw. */
    private Result onTheClock() {
        float seconds = elapsedSeconds();
        if (near.damageDealt() != far.damageDealt()) {
            return new Result(near.damageDealt() > far.damageDealt() ? near : far,
                              Ending.CLOCK_DAMAGE, seconds);
        }
        int nearRooms = livingRooms(near);
        int farRooms = livingRooms(far);
        if (nearRooms != farRooms) {
            return new Result(nearRooms > farRooms ? near : far,
                              Ending.CLOCK_ROOMS, seconds);
        }
        return new Result(null, Ending.CLOCK_DRAW, seconds);
    }

    private static int livingRooms(Side side) {
        int n = 0;
        for (int i = 0; i < side.fortress.Q; i++) {
            if (side.fortress.R[i] != null && side.fortress.R[i].X0 > 0.0f) {
                n++;
            }
        }
        return n;
    }

    /** Run until somebody wins or the clock stops it. */
    public Result run() {
        while (result == null) {
            frame();
        }
        return result;
    }

    /** Run at most this many frames. Null if the duel is still live after them. */
    public Result run(int maxFrames) {
        for (int i = 0; i < maxFrames && result == null; i++) {
            frame();
        }
        return result;
    }

    public Result result() {
        return result;
    }

    public Side near() {
        return near;
    }

    public Side far() {
        return far;
    }

    public Commander nearCommander() {
        return nearCommander;
    }

    public Commander farCommander() {
        return farCommander;
    }

    /** The side a commander is in charge of. */
    public Side sideOf(Commander commander) {
        return commander == nearCommander ? near : far;
    }

    public a1 battle() {
        return battle;
    }

    public int frames() {
        return frames;
    }

    public float elapsedSeconds() {
        return c1.o / (float) Duel.TICKS_PER_SECOND;
    }
}
