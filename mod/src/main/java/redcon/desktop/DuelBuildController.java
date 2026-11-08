package redcon.desktop;

import redcon.duel.DuelBuildCatalog;
import redcon.duel.DuelSession;
import redcon.duel.FortDesign;
import redcon.duel.Side;
import redcon.net.DuelRuntime;

/**
 * Owns one original CUSTOMIZE activity while two local players take turns.
 *
 * <p>The handoff state is a privacy boundary, not decoration: no builder
 * activity or opposing design is exposed until the named seat accepts it.
 * Each pass captures the live editor into that seat's independent snapshot.
 */
public final class DuelBuildController implements AutoCloseable {

    public enum Screen {
        HANDOFF,
        EDITING,
        LOCKED
    }

    private final DuelSession session;
    private final OriginalFortBuilder original;
    private final DuelBuildCatalog catalog;
    private final DuelBuildProfile profile;
    private final DuelRuntime runtime;

    private Screen screen = Screen.HANDOFF;
    private int handoffSide;
    private int activeSide = -1;
    private boolean closed;

    public DuelBuildController(DuelSession session,
                                  OriginalFortBuilder original,
                                  int creditBudget) {
        this(session, original, creditBudget, null);
    }

    public DuelBuildController(DuelRuntime runtime,
                               OriginalFortBuilder original,
                               int creditBudget) {
        this(runtime == null ? null : runtime.session(),
             original, creditBudget, runtime);
    }

    private DuelBuildController(DuelSession session,
                                OriginalFortBuilder original,
                                int creditBudget,
                                DuelRuntime runtime) {
        if (session == null || original == null) {
            throw new IllegalArgumentException("session and builder are required");
        }
        if (session.phase() != DuelSession.Phase.BUILD) {
            throw new IllegalArgumentException("session must start in Build");
        }
        this.session = session;
        this.runtime = runtime;
        // One device builds one fort. The hotseat and development routes hand
        // the machine over, so they start on the near seat; a networked device
        // only ever opens the seat pairing gave it.
        this.handoffSide = runtime == null || runtime.standIn()
            ? Side.NEAR : runtime.localPlayer();
        this.original = original;
        this.catalog = new DuelBuildCatalog(original.activity().D1);
        this.profile = new DuelBuildProfile(
            original.activity(), catalog, creditBudget);
    }

    /** The named player accepts the private handoff and opens only their fort. */
    public void acceptHandoff(int side) throws Exception {
        requireOpen();
        requireBuild();
        if (screen != Screen.HANDOFF || side != handoffSide) {
            throw new IllegalStateException("the screen is reserved for "
                                            + sideName(handoffSide));
        }
        open(side, session.selected(side));
    }

    /** Save this seat and cover the fort before the machine changes hands. */
    public void passControls() {
        requireEditing();
        captureIfChanged();
        handoffSide = activeSide ^ 1;
        activeSide = -1;
        screen = Screen.HANDOFF;
    }

    /** Select one of REDCON's original 23 builder chassis. */
    public void selectChassis(int index) throws Exception {
        requireEditing();
        FortDesign selected = catalog.entry(index).design();
        session.select(activeSide, selected);
        open(activeSide, selected);
    }

    /** Test seam for continuing the lifecycle after inspecting blank Build. */
    void selectDesignForCheck(FortDesign selected) throws Exception {
        requireEditing();
        session.select(activeSide, selected);
        open(activeSide, selected);
    }

    /** True when the editing seat has a previous-round fort to restore. */
    public boolean canLoadLast() {
        return screen == Screen.EDITING && activeSide != -1
            && session.hasLast(activeSide);
    }

    /** Restore this seat's previous-round fort and keep it private. */
    public boolean loadLast() throws Exception {
        requireEditing();
        if (!session.loadLast(activeSide)) {
            return false;
        }
        open(activeSide, session.selected(activeSide));
        return true;
    }

    /**
     * Detach a selection made with REDCON's native chassis buttons from the
     * global content table. Call after the original activity dispatches input.
     */
    public boolean synchronizeOriginalChassis() throws Exception {
        requireEditing();
        FortDesign selected = original.contentSelection();
        if (selected == null) {
            return false;
        }
        session.select(activeSide, selected);
        open(activeSide, selected);
        return true;
    }

    /** Ready this seat, then cover the screen or lock into Commencing. */
    public void ready() {
        requireEditing();
        if (overBudget()) return;
        captureIfChanged();
        int side = activeSide;
        if (runtime == null) {
            session.setReady(side, true);
        } else {
            runtime.commitBuild(side, session.selected(side));
            runtime.setReady(side, true);
        }
        activeSide = -1;
        if (session.phase() == DuelSession.Phase.COMMENCING
                || (runtime != null && !runtime.standIn())) {
            // A networked seat has nobody to hand the machine to: it waits on
            // the peer's own committed readiness instead of a handoff cover.
            screen = Screen.LOCKED;
        } else {
            handoffSide = side ^ 1;
            screen = Screen.HANDOFF;
        }
    }

    /** A seat may withdraw Ready after accepting its private handoff. */
    public void withdrawReady() {
        requireEditing();
        if (runtime == null) {
            session.setReady(activeSide, false);
        } else {
            runtime.setReady(activeSide, false);
        }
    }

    /**
     * Advance the local clock. Live edits are captured before timer expiry so
     * auto-commit cannot race the editor snapshot.
     */
    public void advance(long ticks) {
        requireOpen();
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        if (runtime != null && session.phase() == DuelSession.Phase.BUILD
                && (ticks >= session.buildTicksRemaining()
                    || runtime.phaseDeadlinePassed())) {
            if (screen == Screen.EDITING) {
                captureIfChanged();
                // Every one of REDCON's 23 builder chassis starts with no
                // rooms, and the engine does not survive being made to arm
                // one: its own BATTLE gate refuses it. A seat that never
                // placed a room commits the shipped placeholder fortress
                // instead of hanging both devices at commencement.
                FortDesign committed = session.selected(activeSide);
                if (!original.readyLegal() || overBudget()) {
                    committed = DuelBuildFlow.fallbackFort(session.creditBudget());
                    session.select(activeSide, committed);
                }
                runtime.expireBuild(activeSide, committed, ticks);
                // The deadline has taken this seat's fort. Leaving the editor
                // open would let the next frame's capture re-select a design
                // and, because selecting withdraws Ready, silently retract
                // the commit this device just made: the peer would see a
                // ready commander the local session no longer agrees with.
                activeSide = -1;
                screen = Screen.LOCKED;
            } else if (screen == Screen.LOCKED && !runtime.standIn()) {
                // A locked networked seat has already authored its commit,
                // either by readying or at the deadline above, and has no
                // draft left to auto-commit. Its own readiness is still a
                // packet in flight on the guest, so this cannot wait on the
                // session agreeing; the runtime holds the shared timeline at
                // the deadline until the peer's BUILD_COMMIT lands.
                runtime.advance(ticks);
            } else {
                throw new IllegalStateException(
                    "local Build deadline has no private draft open");
            }
        } else {
            if (session.phase() == DuelSession.Phase.BUILD
                    && ticks >= session.buildTicksRemaining()
                    && screen == Screen.EDITING) {
                captureIfChanged();
            }
            if (runtime == null) {
                session.advance(ticks);
            } else {
                runtime.advance(ticks);
            }
        }
        if (session.phase() != DuelSession.Phase.BUILD) {
            activeSide = -1;
            screen = Screen.LOCKED;
        }
    }

    public Screen screen() {
        return screen;
    }

    public int handoffSide() {
        if (screen != Screen.HANDOFF) {
            return -1;
        }
        return handoffSide;
    }

    public int activeSide() {
        return activeSide;
    }

    public boolean opponentReady(int viewer) {
        requireSide(viewer);
        return session.ready(viewer ^ 1);
    }

    /** Guard against accidentally rendering a fort beneath a handoff cover. */
    public o3.b activity() {
        requireEditing();
        return original.activity();
    }

    public DuelBuildCatalog catalog() {
        return catalog;
    }

    public int remainingCredits() {
        requireEditing();
        return profile.remainingCredits();
    }

    public boolean overBudget() {
        profile.remainingCredits();
        return profile.currentDesignOverBudget();
    }

    public DuelSession session() {
        return session;
    }

    @Override
    public void close() {
        if (!closed) {
            profile.close();
            closed = true;
            activeSide = -1;
            screen = Screen.LOCKED;
        }
    }

    private void open(int side, FortDesign design) throws Exception {
        FortDesign upgraded = catalog.maximumTiers(design);
        if (upgraded.layoutHash() != design.layoutHash()) session.select(side, upgraded);
        design = upgraded;
        profile.prepare(design);
        original.open(design);
        DuelBuildProfile.refreshEditor(original.activity());
        activeSide = side;
        screen = Screen.EDITING;
    }

    private void captureIfChanged() {
        FortDesign captured = original.capture();
        if (captured.layoutHash() != session.selected(activeSide).layoutHash()) {
            session.select(activeSide, captured);
        }
    }

    private void requireEditing() {
        requireOpen();
        requireBuild();
        if (screen != Screen.EDITING || activeSide == -1) {
            throw new IllegalStateException("no player is editing");
        }
    }

    private void requireBuild() {
        if (session.phase() != DuelSession.Phase.BUILD) {
            throw new IllegalStateException("not in Build: " + session.phase());
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("build controller is closed");
        }
    }

    private static void requireSide(int side) {
        if (side != Side.NEAR && side != Side.FAR) {
            throw new IllegalArgumentException("bad side " + side);
        }
    }

    private static String sideName(int side) {
        return side == Side.NEAR ? "Player 1" : "Player 2";
    }
}
