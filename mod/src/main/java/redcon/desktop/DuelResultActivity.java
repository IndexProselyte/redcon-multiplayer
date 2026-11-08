package redcon.desktop;

import redcon.duel.DuelSession;
import redcon.duel.Side;

/**
 * The duel's end-of-round dialog.
 *
 * <p>It is raised the way REDCON raises its own in-battle dialog: the battle
 * activity stays live underneath, this layer paints it, {@code f3.e.k} supplies
 * the modal dim, and the actions are ordinary native menu rows.</p>
 *
 * <p>Those rows are the sub-screen list {@link DuelMenu} builds, the same one
 * Browse and Pairing use, rather than the big right-aligned title-menu rows
 * {@code f3.e.L}/{@code F} build. Pairing already shows a commander and their
 * readiness as a label/value pair; a result is the same shape of information
 * and reads as a main menu floating over the battlefield when it is built the
 * other way. It also keeps the name and the state in separate halves, so the
 * rows line up as a column instead of running ragged against a right edge, and
 * it avoids the large menu face, which has no hyphen glyph and was rendering
 * STAND-IN OPPONENT as STANDIN OPPONENT.</p>
 */
public final class DuelResultActivity extends f3.e {

    /** The dim {@code f3.e.R()} lays down for a modal, reused verbatim. */
    private static final int MODAL_DIM = -1442840576;

    static final int EVENT_NEAR_READY = 98;
    static final int EVENT_FAR_READY = 99;
    static final int EVENT_LEAVE = 100;

    private final DuelBattleFlow flow;
    private final DuelSession session;

    /**
     * Outcome and session score live in the same native status plate the Build
     * and Battle screens use. The large menu face has no digit glyphs, so the
     * score cannot be carried by a row label.
     */
    private final o3.z plate = new o3.z(3);

    DuelResultActivity(DuelBattleFlow flow, DuelSession session) {
        this.flow = flow;
        this.session = session;
    }

    @Override
    public void B() {
        super.B();
        refreshPlate();
    }

    /** Rebuild the rows and the plate after a readiness change. */
    void refresh() {
        plate.s();
        a0();
        d0();
        refreshPlate();
    }

    @Override
    public void I(i3.j root, i3.b list) {
        DuelMenu.column(list);
        DuelMenu.heading(list, DuelStrings.of(DuelStrings.COMMANDERS));
        // A single-device duel votes for both commanders here; a networked one
        // votes only for itself, and the opponent's row is a readout of what
        // their device has committed.
        DuelMenu.row(list, this, votable(Side.NEAR) ? EVENT_NEAR_READY : 0,
                     session.playerName(Side.NEAR), rematchValue(Side.NEAR), -1);
        DuelMenu.row(list, this, votable(Side.FAR) ? EVENT_FAR_READY : 0,
                     session.playerName(Side.FAR), rematchValue(Side.FAR), -1);
    }

    /** True when this device may cast the vote on that commander's row. */
    private boolean votable(int side) {
        return flow == null || flow.localVote(side);
    }

    @Override
    public void G(i3.j root) {
        // Leaving goes where REDCON puts a screen's own action, the bottom
        // right corner, built by the shell's N(...) exactly as Pairing builds
        // BEGIN BUILD. A third row of the same weight under COMMANDERS reads
        // as a third commander; a corner action does not. It is also spelled
        // out rather than left to a top-left Back, because backing out of a
        // result forfeits the duel and an arrow does not say so.
        N(root, EVENT_LEAVE, DuelStrings.of(DuelStrings.LEAVE_DUEL), null);
    }

    @Override
    public void X(int event, Object data) {
        if (event == EVENT_NEAR_READY) {
            flow.toggleRematch(Side.NEAR);
        } else if (event == EVENT_FAR_READY) {
            flow.toggleRematch(Side.FAR);
        } else if (event == EVENT_LEAVE) {
            flow.leave();
        }
    }

    @Override
    public void C() {
        // Only this dialog's own widgets are recorded. The battle screen's HUD
        // pass is deliberately not driven: REDCON's own in-battle cutscene
        // shows the fortresses with no HUD at all, and driving it here left the
        // ability buttons, both resource bars and the room tooltip live under
        // the result, with the tooltip plate stacked directly beneath this
        // one. The world itself is still painted, in q().
        super.C();
    }

    @Override
    public void q() {
        // f3.e.R() would render the title-menu scene here. Render the ended
        // fortresses instead, then lay down the same dim R() uses for a modal.
        flow.paintFrozenBattleWorld();
        mojo.b0.b(MODAL_DIM, 0, 0, mojo.b.o, mojo.b.p);
        j.n();
    }

    @Override
    public void j() {
        flow.leave();
    }

    private void refreshPlate() {
        DuelSession.RoundResult result = session.result();
        String outcome = result.isDraw()
            ? DuelStrings.of(DuelStrings.DRAW)
            : session.playerName(result.winner).toUpperCase()
              + " " + DuelStrings.of(DuelStrings.WINS);
        // The native row list is top-anchored on the right half, so the result
        // panel sits in the clear band below it rather than over its first row.
        plate.C(j, mojo.b.o / 2, (mojo.b.p / 2) + 80, 16, outcome,
                DuelStrings.of(DuelStrings.SESSION) + " "
                + session.score(Side.NEAR) + " - " + session.score(Side.FAR)
                + "     " + rematchState());
    }

    private String rematchState() {
        boolean near = session.rematchReady(Side.NEAR);
        boolean far = session.rematchReady(Side.FAR);
        if (near && far) {
            return DuelStrings.of(DuelStrings.REMATCH_STARTING);
        }
        if (near) {
            return waitingFor(Side.FAR);
        }
        if (far) {
            return waitingFor(Side.NEAR);
        }
        return DuelStrings.of(DuelStrings.REMATCH_WHEN_BOTH_READY);
    }

    private String waitingFor(int side) {
        return DuelStrings.of(DuelStrings.WAITING_FOR)
             + " " + session.playerName(side).toUpperCase();
    }

    o3.z plate() {
        return plate;
    }

    /** A commander's readiness, as the row's right-hand value. */
    private String rematchValue(int side) {
        return DuelStrings.of(
            session.rematchReady(side) ? DuelStrings.READY : DuelStrings.REMATCH);
    }
}
