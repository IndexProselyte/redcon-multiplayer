package redcon.duel;

import redcon.desktop.Duel;

/**
 * Phase 05's local match flow, independent of widgets and transport.
 *
 * <p>The same state machine serves hotseat now and LAN later. Networking will
 * report remote state changes into it; it does not get a different set of
 * rules. Time is expressed in REDCON's 7920-Hz battle ticks so a shared Phase
 * 04 deadline can replace the local clock without changing this class.
 */
public final class DuelSession {

    public static final int BUILD_SECONDS = 60;
    public static final int COMMENCING_SECONDS = 3;

    private static final long COMMENCING_TICKS =
        (long) COMMENCING_SECONDS * Duel.TICKS_PER_SECOND;

    public enum Phase {
        BUILD,
        COMMENCING,
        BATTLE,
        RESULT,
        CLOSED
    }

    /** The result data the session needs after a battle object is discarded. */
    public static final class RoundResult {
        /** {@link Side#NEAR}, {@link Side#FAR}, or -1 for a draw. */
        public final int winner;
        public final DuelMatch.Ending ending;
        public final float seconds;

        public RoundResult(int winner, DuelMatch.Ending ending, float seconds) {
            if (winner != Side.NEAR && winner != Side.FAR && winner != -1) {
                throw new IllegalArgumentException("bad winner " + winner);
            }
            if (ending == null) {
                throw new IllegalArgumentException("ending is null");
            }
            this.winner = winner;
            this.ending = ending;
            this.seconds = seconds;
        }

        public boolean isDraw() {
            return winner == -1;
        }
    }

    private final String nearName;
    private final String farName;
    private final FortDesign defaultNear;
    private final FortDesign defaultFar;
    private final int creditBudget;
    private final int buildSeconds;
    private final int matchSeconds;
    private final long buildTicks;

    private final FortDesign[] selected = new FortDesign[2];
    private final FortDesign[] committed = new FortDesign[2];
    private final FortDesign[] last = new FortDesign[2];
    private final boolean[] ready = new boolean[2];
    private final boolean[] rematchReady = new boolean[2];
    private final int[] score = new int[2];

    private Phase phase;
    private long phaseTicks;
    private int round;
    private RoundResult result;
    private int leaver = -1;

    public DuelSession(String nearName, String farName,
                       FortDesign defaultNear, FortDesign defaultFar) {
        this(nearName, farName, defaultNear, defaultFar,
             DuelBuildCatalog.DEFAULT_CREDIT_BUDGET,
             BUILD_SECONDS, DuelMatch.DEFAULT_MATCH_SECONDS);
    }

    /** Create a session from the rules agreed on the Preparation screen. */
    public DuelSession(String nearName, String farName,
                       FortDesign defaultNear, FortDesign defaultFar,
                       int creditBudget, int buildSeconds, int matchSeconds) {
        this.nearName = requireName(nearName);
        this.farName = requireName(farName);
        if (defaultNear == null || defaultFar == null) {
            throw new IllegalArgumentException("both default forts are required");
        }
        if (creditBudget <= 0 || buildSeconds <= 0 || matchSeconds <= 0) {
            throw new IllegalArgumentException("duel rules must be positive");
        }
        this.defaultNear = defaultNear.copy();
        this.defaultFar = defaultFar.copy();
        this.creditBudget = creditBudget;
        this.buildSeconds = buildSeconds;
        this.matchSeconds = matchSeconds;
        this.buildTicks = (long) buildSeconds * Duel.TICKS_PER_SECOND;
        beginBuild();
    }

    /** Choose a build. Editing after Ready automatically withdraws readiness. */
    public void select(int side, FortDesign design) {
        requirePhase(Phase.BUILD);
        requireSide(side);
        if (design == null) {
            throw new IllegalArgumentException("design is null");
        }
        selected[side] = design.copy();
        ready[side] = false;
    }

    /** True when this player has a previous committed design to restore. */
    public boolean hasLast(int side) {
        requireSide(side);
        return last[side] != null;
    }

    /** Restore this player's previous committed design, if there is one. */
    public boolean loadLast(int side) {
        requirePhase(Phase.BUILD);
        requireSide(side);
        if (last[side] == null) {
            return false;
        }
        select(side, last[side]);
        return true;
    }

    /** Ready is revocable until the instant both players are ready. */
    public void setReady(int side, boolean value) {
        requirePhase(Phase.BUILD);
        requireSide(side);
        ready[side] = value;
        if (ready[Side.NEAR] && ready[Side.FAR]) {
            commitAndCommence();
        }
    }

    /** Advance the Build/Commencing clocks, carrying overflow across a boundary. */
    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        long remaining = ticks;
        while (remaining > 0 && (phase == Phase.BUILD || phase == Phase.COMMENCING)) {
            long limit = phase == Phase.BUILD ? buildTicks : COMMENCING_TICKS;
            long step = Math.min(remaining, limit - phaseTicks);
            phaseTicks += step;
            remaining -= step;
            if (phaseTicks == limit) {
                if (phase == Phase.BUILD) {
                    commitAndCommence();
                } else {
                    phase = Phase.BATTLE;
                    phaseTicks = 0;
                }
            }
        }
    }

    /** Move a completed DuelMatch into the Result state and update the score. */
    public void finish(DuelMatch.Result battleResult) {
        requirePhase(Phase.BATTLE);
        if (battleResult == null) {
            throw new IllegalArgumentException("battle result is null");
        }
        int winner = battleResult.winner == null ? -1 : battleResult.winner.index;
        finish(new RoundResult(winner, battleResult.ending, battleResult.seconds));
    }

    /** Test/UI form which does not require a live battle object. */
    public void finish(RoundResult roundResult) {
        requirePhase(Phase.BATTLE);
        if (roundResult == null) {
            throw new IllegalArgumentException("round result is null");
        }
        result = roundResult;
        if (!roundResult.isDraw()) {
            score[roundResult.winner]++;
        }
        phase = Phase.RESULT;
        phaseTicks = 0;
        rematchReady[Side.NEAR] = false;
        rematchReady[Side.FAR] = false;
    }

    /** Both Result-screen Ready presses return the session to fort selection. */
    public void setRematchReady(int side, boolean value) {
        requirePhase(Phase.RESULT);
        requireSide(side);
        rematchReady[side] = value;
        if (rematchReady[Side.NEAR] && rematchReady[Side.FAR]) {
            beginBuild();
        }
    }

    /** Leaving is legal from every live state. */
    public void leave(int side) {
        if (phase == Phase.CLOSED) {
            return;
        }
        requireSide(side);
        leaver = side;
        phase = Phase.CLOSED;
        phaseTicks = 0;
    }

    private void beginBuild() {
        phase = Phase.BUILD;
        phaseTicks = 0;
        round++;
        result = null;
        selected[Side.NEAR] = defaultNear.copy();
        selected[Side.FAR] = defaultFar.copy();
        committed[Side.NEAR] = null;
        committed[Side.FAR] = null;
        ready[Side.NEAR] = false;
        ready[Side.FAR] = false;
        rematchReady[Side.NEAR] = false;
        rematchReady[Side.FAR] = false;
    }

    private void commitAndCommence() {
        committed[Side.NEAR] = selected[Side.NEAR].copy();
        committed[Side.FAR] = selected[Side.FAR].copy();
        last[Side.NEAR] = committed[Side.NEAR].copy();
        last[Side.FAR] = committed[Side.FAR].copy();
        phase = Phase.COMMENCING;
        phaseTicks = 0;
    }

    public Phase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public String playerName(int side) {
        requireSide(side);
        return side == Side.NEAR ? nearName : farName;
    }

    public FortDesign selected(int side) {
        requireSide(side);
        return selected[side].copy();
    }

    /** Null during Build and Commencing: the reveal happens at battle start. */
    public FortDesign opponentDesign(int viewer) {
        requireSide(viewer);
        if (phase != Phase.BATTLE && phase != Phase.RESULT) {
            return null;
        }
        return committed[viewer ^ 1].copy();
    }

    /**
     * The committed design used to arm the battle.
     *
     * <p>Available from COMMENCING onwards: both forts are locked by then, and
     * the commencement beat exists precisely to show them before the fight.</p>
     */
    public FortDesign battleDesign(int side) {
        requireSide(side);
        if (phase != Phase.COMMENCING && phase != Phase.BATTLE && phase != Phase.RESULT) {
            throw new IllegalStateException("battle designs are not revealed in " + phase);
        }
        return committed[side].copy();
    }

    public boolean ready(int side) {
        requireSide(side);
        return ready[side];
    }

    public boolean rematchReady(int side) {
        requireSide(side);
        return rematchReady[side];
    }

    public int score(int side) {
        requireSide(side);
        return score[side];
    }

    public RoundResult result() {
        return result;
    }

    public int leaver() {
        return leaver;
    }

    public int creditBudget() {
        return creditBudget;
    }

    public int buildSeconds() {
        return buildSeconds;
    }

    public int matchSeconds() {
        return matchSeconds;
    }

    public float buildSecondsRemaining() {
        if (phase != Phase.BUILD) {
            return 0.0f;
        }
        return (buildTicks - phaseTicks) / (float) Duel.TICKS_PER_SECOND;
    }

    public long buildTicksRemaining() {
        return phase == Phase.BUILD ? buildTicks - phaseTicks : 0;
    }

    public float commencingSecondsRemaining() {
        if (phase != Phase.COMMENCING) {
            return 0.0f;
        }
        return (COMMENCING_TICKS - phaseTicks) / (float) Duel.TICKS_PER_SECOND;
    }

    public long commencingTicksRemaining() {
        return phase == Phase.COMMENCING ? COMMENCING_TICKS - phaseTicks : 0;
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("expected " + expected + ", was " + phase);
        }
    }

    private static void requireSide(int side) {
        if (side != Side.NEAR && side != Side.FAR) {
            throw new IllegalArgumentException("bad side " + side);
        }
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("player name is blank");
        }
        return value;
    }
}
