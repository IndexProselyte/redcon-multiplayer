package redcon.desktop;

import mojo.EventListener;
import redcon.duel.DuelBuildCatalog;
import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.duel.Side;
import redcon.net.DuelRuntime;
import redcon.net.InProcessDuelRuntime;

/**
 * Product-shell owner for the local duel Build phase.
 *
 * <p>It does not paint a replacement HUD. It opens REDCON's recovered
 * {@code o3.b} CUSTOMIZE activity and temporarily rebinds that screen's own
 * bottom-right BATTLE control as READY. All changes are restored on exit so
 * campaign customization continues to use the original event path.</p>
 */
public final class DuelBuildFlow extends EventListener implements AutoCloseable {

    private static DuelBuildFlow active;

    private final DuelPairingActivity pairing;
    private final OriginalFortBuilder original;
    private final DuelBuildController build;
    private final DuelRuntime runtime;
    private final int localSide;
    static final int EVENT_LOAD_LAST = 102;

    private final o3.z status = new o3.z(3);
    private final o3.z loadLast = new o3.z(3);
    private boolean bound;
    private boolean readyWasEnabled;
    private boolean closed;

    private DuelBuildFlow(DuelPairingActivity pairing, DuelSession resumed,
                          DuelRuntime resumedRuntime)
            throws Exception {
        this.pairing = pairing;
        original = new OriginalFortBuilder();
        original.returnTo(pairing);
        DuelBuildCatalog catalog = new DuelBuildCatalog(original.activity().D1);
        FortDesign opponentFort = fallbackFort(pairing.creditBudget());
        // Start the local commander on a genuine customizable chassis. The
        // former tutorial setup has three fixed guns and no defense mounts,
        // so REDCON correctly had no defense placement markers to show.
        FortDesign defaultFort = catalog.entry(0).design();
        DuelSession session = resumed == null
            ? new DuelSession(DuelStrings.of(DuelStrings.PLAYER_ONE),
                              DuelStrings.of(DuelStrings.STAND_IN),
                              defaultFort, opponentFort,
                              pairing.creditBudget(), pairing.buildSeconds(),
                              pairing.matchSeconds())
            : resumed;
        runtime = resumed == null
            ? new InProcessDuelRuntime(Side.NEAR, Side.NEAR, session)
            : resumedRuntime;
        if (runtime == null || runtime.session() != session) {
            throw new IllegalArgumentException(
                "resumed Build requires its committed event runtime");
        }
        localSide = runtime.localPlayer();
        if (!runtime.standIn()) {
            // Start a networked seat on the same customizable chassis the
            // single-device route opens with. Selection is private editor
            // state - only BUILD_COMMIT crosses the boundary - so this is not
            // a second path around the consumer.
            session.select(localSide, defaultFort);
        }
        build = new DuelBuildController(
            runtime, original, session.creditBudget());
        build.acceptHandoff(localSide);
        commitOpponent(runtime, opponentFort);
        bindReadyControl();
    }

    /** Start Build from the native pairing menu. */
    public static void start(DuelPairingActivity pairing) {
        closeActive();
        try {
            active = new DuelBuildFlow(pairing, null, null);
        } catch (Exception failed) {
            active = null;
            throw new IllegalStateException("could not open REDCON's fort editor", failed);
        }
    }

    /**
     * Start Build on a paired peer session.
     *
     * <p>The runtime already exists here: it was created when PAIR_OK landed
     * and it built its own {@code DuelSession} from the rules revision the two
     * clients agreed on. Nothing local is stood up, and no fort is committed
     * for the opponent - theirs arrives from their device.</p>
     */
    public static void startNetworked(DuelPairingActivity pairing,
                                      DuelRuntime runtime) {
        if (runtime == null || runtime.session() == null) {
            throw new IllegalArgumentException(
                "a started networked runtime is required");
        }
        closeActive();
        try {
            active = new DuelBuildFlow(pairing, runtime.session(), runtime);
        } catch (Exception failed) {
            active = null;
            throw new IllegalStateException(
                "could not open REDCON's fort editor", failed);
        }
    }

    /** Continue an existing scored session after both rematch votes. */
    static void resume(DuelPairingActivity pairing, DuelSession session,
                       DuelRuntime runtime) {
        closeActive();
        try {
            active = new DuelBuildFlow(pairing, session, runtime);
        } catch (Exception failed) {
            active = null;
            throw new IllegalStateException("could not reopen REDCON's fort editor", failed);
        }
    }

    /** Called by the product frame pump; the recovered activity still paints itself. */
    public static void updateActive(long ticks) {
        DuelBuildFlow flow = active;
        if (flow == null || flow.closed) {
            return;
        }
        if (!flow.ownsCurrentScreen()) {
            // Build is entered by swapping mojo.b.i.b directly, so the screen
            // it covers is never deactivated and never gets a B() call on the
            // way back. Watching the current activity closes the duel profile
            // on every exit path rather than only the ones that announce
            // themselves - leaving it open would strand the duel's granted
            // inventory, level and purse on the campaign profile.
            closeActive();
            return;
        }
        try {
            DuelSession.Phase before = flow.build.session().phase();
            // Committed events arrive here, on the game thread, before
            // anything reads the session. The opponent's commit and readiness
            // are what move Build on, so this has to run first.
            flow.runtime.update();
            if (flow.endedByPeer()) {
                return;
            }
            if (flow.build.session().phase() == DuelSession.Phase.BUILD
                    && flow.build.screen()
                        == DuelBuildController.Screen.EDITING) {
                // The controller's screen only catches up with the session
                // inside advance(), so between the peer's committed readiness
                // landing above and that call the editor is still open on a
                // session which has already left Build. Editing it then is
                // what the controller refuses; the frame below closes it.
                flow.build.synchronizeOriginalChassis();
            }
            flow.build.advance(ticks);
            // After advance, because the deadline closes the editor there and
            // a seat which has just been locked has a different thing to say.
            flow.updateStatus();
            flow.traceState(ticks);
            if (before == DuelSession.Phase.BUILD
                    && enteringBattle(flow.build.session().phase())) {
                flow.unbindReadyControl();
                flow.enterBattle();
            }
        } catch (Exception failed) {
            flow.abort(failed);
        }
    }

    /**
     * Leave when the peer has gone, and say so rather than pretending.
     *
     * <p>Phase 04's rule for a broken connection is that it ends or recovers
     * explicitly. The runtime has already closed the session by the time this
     * reports true, so the shell's job is only to take the duel off screen and
     * put the reason on the pairing lobby.</p>
     */
    private boolean endedByPeer() {
        if (runtime.link() == DuelRuntime.Link.LIVE
                && build.session().phase() != DuelSession.Phase.CLOSED) {
            return false;
        }
        System.out.println("   duel flow: duel ended - " + runtime.link());
        close();
        mojo.b.i.g(pairing);
        return true;
    }

    /**
     * True while Build, its private handoff, or a transition between them is
     * the current screen.
     */
    private boolean ownsCurrentScreen() {
        mojo.Activity current = mojo.b.i.b;
        return current == original.activity() || current == mojo.b.x;
    }

    /** Pairing is rebuilt by REDCON when Back returns from Build or handoff. */
    public static void closeIfReturningTo(DuelPairingActivity pairing) {
        if (active != null && active.pairing == pairing) {
            closeActive();
        }
    }

    public static DuelBuildFlow active() {
        return active;
    }

    /** Seed a legal fort after visual checks so automation can reach Battle. */
    static void useLegalFortForCheck() throws Exception {
        if (active == null || active.closed) {
            throw new IllegalStateException("no active Build flow");
        }
        active.build.selectDesignForCheck(firstLegalFort());
        active.updateStatus();
    }

    o3.z statusPlate() {
        return status;
    }

    o3.z loadLastPlate() {
        return build.canLoadLast() ? loadLast : null;
    }

    DuelSession.Phase buildPhase() {
        return build.session().phase();
    }

    /** Current session for product-path regression checks. */
    DuelSession session() {
        return build.session();
    }

    /** Expire Build through its normal clock/update path. */
    void expireForCheck() {
        if (closed || build.session().phase() != DuelSession.Phase.BUILD) {
            throw new IllegalStateException("no live duel Build");
        }
        updateActive(build.session().buildTicksRemaining());
    }

    /**
     * Commit the opponent's fortress up front.
     *
     * <p>Only this device's commander builds here. Over the network the
     * opponent's committed design and readiness arrive from their own device;
     * on the development route they come from an authored campaign fortress.
     * Either way, readying up commences the match instead of waiting for a
     * second seat that is never going to arrive.</p>
     */
    private static void commitOpponent(DuelRuntime runtime,
                                       FortDesign fort) {
        DuelSession session = runtime.session();
        if (!runtime.standIn() || session.phase() != DuelSession.Phase.BUILD) {
            return;
        }
        runtime.commitBuild(Side.FAR, fort);
        runtime.setReady(Side.FAR, true);
    }

    @Override
    public void handleEvent(int event, Object source, Object data) {
        // A second native Ready event can already be queued when the first
        // locks this seat. It must not enter the editing-only controller again,
        // even before the guest's Ready acknowledgement reaches its session.
        if (closed || build.screen() != DuelBuildController.Screen.EDITING
                || build.session().phase() != DuelSession.Phase.BUILD) {
            return;
        }
        if (event == EVENT_LOAD_LAST) {
            try {
                build.loadLast();
            } catch (Exception failed) {
                abort(failed);
            }
            return;
        }
        if (source != original.activity().A1 || event != 12) {
            return;
        }
        if (!original.readyLegal()) {
            // Preserve REDCON's original warning/modal instead of allowing an
            // empty chassis into a battle configuration the engine cannot arm.
            original.activity().W0(12, source, data);
            return;
        }
        build.ready();
        if (!enteringBattle(build.session().phase())) {
            // A networked seat readies alone. Commencement is the committed
            // consequence of the peer's own readiness, and updateActive raises
            // Battle when it arrives.
            updateStatus();
            return;
        }
        unbindReadyControl();
        try {
            enterBattle();
        } catch (Exception failed) {
            abort(failed);
        }
    }

    void leave() {
        if (!closed && build.session().phase() != DuelSession.Phase.CLOSED) {
            runtime.leave(localSide, "left Build");
        }
        close();
        mojo.b.i.g(pairing);
    }

    /**
     * Has the session left Build for a live battle?
     *
     * <p>Both callers used to ask whether the phase was exactly
     * {@code COMMENCING}, which assumed the host's two clocks arrive in
     * separate pumps. On a real LAN they do not: {@code READY_SET},
     * {@code PHASE_CLOCK COMMENCING} and {@code PHASE_CLOCK BATTLE} were
     * observed landing in one {@code runtime.update()} on a handset, leaving
     * the phase at {@code BATTLE} and the equality test false. The battle
     * screen then never armed, the device sat on the Build screen, and its
     * peer waited at the barrier until the duel timed out.</p>
     *
     * <p>The question that was always meant is whether Build is over, so ask
     * that. {@link DuelBattleController} already accepts either phase.</p>
     */
    private static boolean enteringBattle(DuelSession.Phase phase) {
        return phase == DuelSession.Phase.COMMENCING
            || phase == DuelSession.Phase.BATTLE;
    }

    private void enterBattle() throws Exception {
        DuelSession session = build.session();
        System.out.println("   duel flow: closing Build profile");
        close();
        System.out.println("   duel flow: opening native Battle");
        DuelBattleFlow.start(session, pairing, runtime);
        System.out.println("   duel flow: native Battle opened");
    }

    private void bindReadyControl() {
        o3.b editor = original.activity();
        readyWasEnabled = (editor.A1.d & 1) != 0;
        editor.A1.removeListener(editor);
        editor.A1.ensureListener(this);
        ((c3.h) editor.A1.B).o(0, DuelStrings.of(DuelStrings.READY));
        bound = true;
        updateStatus();
    }

    private void updateStatus() {
        int credits = build.screen() == DuelBuildController.Screen.EDITING
            ? build.remainingCredits() : 0;
        original.activity().z1.f = false;
        original.activity().z1.v(false);
        original.activity().A1.v(build.screen() == DuelBuildController.Screen.EDITING
            && build.session().phase() == DuelSession.Phase.BUILD && !build.overBudget());
        if (build.screen() != DuelBuildController.Screen.EDITING) {
            loadLast.s();
            if (!runtime.standIn()
                    && build.session().phase() == DuelSession.Phase.BUILD) {
                // This seat has committed and the peer has not. Say so: an
                // empty screen is indistinguishable from a hung connection.
                status.C(original.activity().m1, mojo.b.o / 2, 14, 16,
                         build.session().playerName(localSide),
                         DuelStrings.of(DuelStrings.WAITING_FOR_OPPONENT));
                return;
            }
            status.s();
            return;
        }
        int side = build.activeSide();
        int seconds = Math.max(0,
            (int) Math.ceil(build.session().buildSecondsRemaining()));
        // o3.z sizes its panel from the second line, so keep the identity on
        // the first and let the wider duel readout drive the width.
        String title = build.session().playerName(side);
        String opponent = DuelStrings.of(build.opponentReady(side)
            ? DuelStrings.OPPONENT_READY : DuelStrings.OPPONENT_BUILDING);
        String detail = DuelStrings.of(DuelStrings.BUILD) + " " + (seconds / 60) + ":"
                      + String.format("%02d", seconds % 60)
                      + "     $" + credits + " / $" + build.session().creditBudget()
                      + "     " + opponent;
        if (build.overBudget()) detail += "     OVER BUDGET";
        boolean inventoryOpen = original.activity().p1.h();
        int statusX = inventoryOpen ? Math.max(160, (mojo.b.o - original.activity().p1.i) / 2) : mojo.b.o / 2;
        if (inventoryOpen) {
            title = DuelUiText.fit(h3.c.X, title, Math.max(180, statusX * 2 - 32));
            detail = "BUILD " + (seconds / 60) + ":" + String.format("%02d", seconds % 60)
                + "     $" + credits + " / $" + build.session().creditBudget();
            if (build.overBudget()) detail += " OVER BUDGET";
        }
        status.C(original.activity().m1, statusX, inventoryOpen ? 180 : 14, 16, title, detail);
        updateLoadLast();
    }

    /**
     * Offer the previous round's fortress back, in the same status-plate
     * grammar as the duel readout above it and as a real tap target. It is
     * absent in the first round, when there is nothing to restore.
     */
    private void updateLoadLast() {
        if (!build.canLoadLast()) {
            loadLast.s();
            return;
        }
        loadLast.b = EVENT_LOAD_LAST;
        loadLast.v(true);
        loadLast.ensureListener(this);
        loadLast.C(original.activity().m1, mojo.b.o / 2, 86, 16,
                   DuelStrings.of(DuelStrings.LOAD_LAST),
                   DuelStrings.of(DuelStrings.YOUR_PREVIOUS_FORTRESS));
        loadLast.u(loadLast.u, loadLast.v, loadLast.w, loadLast.x);
    }

    private void unbindReadyControl() {
        if (!bound) {
            return;
        }
        status.s();
        loadLast.s();
        o3.b editor = original.activity();
        editor.A1.removeListener(this);
        editor.A1.v(readyWasEnabled);
        ((c3.h) editor.A1.B).o(0, mojo.Base.NLS("BATTLE"));
        bound = false;
    }

    private String tracedState;

    /** Report Build's committed state whenever it actually changes. */
    private void traceState(long ticks) {
        DuelSession session = build.session();
        String state = session.phase() + " screen=" + build.screen()
            + " near=" + session.ready(redcon.duel.Side.NEAR)
            + " far=" + session.ready(redcon.duel.Side.FAR);
        if (!state.equals(tracedState)) {
            tracedState = state;
            System.err.println("duel build state " + state
                + " remaining=" + session.buildTicksRemaining()
                + " ticks=" + ticks);
        }
    }

    private void abort(Exception failed) {
        System.err.println("duel Build aborted: " + failed);
        leave();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        unbindReadyControl();
        original.activity().A1.ensureListener(original.activity());
        build.close();
        closed = true;
        if (active == this) {
            active = null;
        }
    }

    private static void closeActive() {
        if (active != null) {
            active.close();
        }
    }

    /**
     * The placeholder both fortresses hold until a commit replaces them.
     *
     * <p>A networked session is constructed before either player has built
     * anything, and {@code DuelSession} needs a legal design for each side.
     * This is a real shipped fortress rather than an invented one, and it is
     * replaced on both devices by the committed {@code BUILD_COMMIT}.</p>
     */
    static FortDesign defaultChassis() {
        return firstLegalFort();
    }

    /** Timer fallback follows the same tier and budget rules as an edited loadout. */
    static FortDesign fallbackFort(int budget) {
        DuelBuildCatalog catalog = new DuelBuildCatalog(q3.b.j0.e0.D1);
        FortDesign design = catalog.maximumTiers(firstLegalFort());
        o3.z0 fort = design.instantiate();
        while (fort.n > 1 && catalog.cost(design.captureEdited(fort)).equipment() > budget) fort.n--;
        fort.o = java.util.Arrays.copyOf(fort.o, fort.n * 5);
        FortDesign result = design.captureEdited(fort);
        if (catalog.cost(result).equipment() > budget) throw new IllegalArgumentException("budget cannot fund fallback fortress");
        return result;
    }

    private static FortDesign firstLegalFort() {
        for (mojo.f content : mojo.f.c) {
            if (content instanceof o3.z0) {
                o3.z0 fort = (o3.z0) content;
                if ("973rd Aux Light Artillery Station".equals(fort.d)
                        && fort.n == 3) {
                    return FortDesign.capture(fort);
                }
            }
        }
        throw new IllegalStateException("the first legal campaign fort is missing");
    }
}
