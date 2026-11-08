package redcon.desktop;

import redcon.duel.DuelSession;

/**
 * The three-second beat between both fortresses locking and the fight starting.
 *
 * <p>It is a transition over the armed battle, not a screen of its own: the
 * battle activity is already built, both fortresses are on screen, and this
 * layer holds the simulation, dims the arena the way {@code f3.e.R} dims one
 * for a modal, and counts down in the game's own status plate. Input is
 * captured for the duration, so neither commander can act before the clock
 * starts.</p>
 */
public final class DuelCommencingActivity extends f3.e {

    /** The dim {@code f3.e.R()} lays down for a modal, reused verbatim. */
    private static final int MODAL_DIM = -1442840576;

    private final DuelBattleFlow flow;
    private final DuelSession session;
    private final o3.z plate = new o3.z(3);

    DuelCommencingActivity(DuelBattleFlow flow, DuelSession session) {
        this.flow = flow;
        this.session = session;
    }

    @Override
    public void B() {
        super.B();
        refresh();
    }

    /** Repaint the countdown for this frame. */
    void refresh() {
        int seconds = (int) Math.ceil(session.commencingSecondsRemaining());
        plate.C(j, mojo.b.o / 2, (mojo.b.p / 2) - 40, 16,
                DuelStrings.of(DuelStrings.FORTRESSES_LOCKED),
                DuelStrings.of(DuelStrings.COMMENCING_IN)
                    + " " + Math.max(1, seconds));
    }

    o3.z plate() {
        return plate;
    }

    /** Take the countdown off screen as the fight starts. */
    void dismiss() {
        plate.s();
    }

    @Override
    public void I(i3.j root, i3.b list) {
        // No controls: the beat is not interactive.
    }

    @Override
    public void C() {
        flow.paintFrozenBattle();
        super.C();
    }

    @Override
    public void q() {
        flow.paintFrozenBattleWorld();
        mojo.b0.b(MODAL_DIM, 0, 0, mojo.b.o, mojo.b.p);
        j.n();
    }

    @Override
    public void j() {
        // Back is inert for the three seconds; leaving is offered again the
        // moment the fight starts.
    }
}
