package redcon.desktop;

import mojo.GameLoop;
import redcon.duel.DuelMatch;
import redcon.duel.DuelSession;
import redcon.duel.Side;
import redcon.duel.Order;
import redcon.net.DuelRuntime;

/** Product-shell owner for native Battle, Result, and rematch routing. */
public final class DuelBattleFlow implements AutoCloseable {

    /** REDCON's own pause control on the battle HUD ({@code a1.c0} case 4). */
    private static final int STOCK_PAUSE_EVENT = 4;

    private static DuelBattleFlow active;
    private static boolean simulationHeld;

    private final DuelSession session;
    private final DuelPairingActivity pairing;
    private final DuelRuntime runtime;
    private final DuelBattleController battle;
    private final o3.z status = new o3.z(3);
    private DuelCommencingActivity commencing;
    private i3.q stockPause;
    private i3.b stockPauseParent;
    private short pauseX;
    private short pauseY;
    private short pauseW;
    private short pauseH;
    private boolean painting;
    private boolean resultShown;
    private boolean closed;
    private int stalledFrames;
    private final DuelSeatMarker seatMarker = new DuelSeatMarker();

    private DuelBattleFlow(DuelSession session, DuelPairingActivity pairing,
                           DuelRuntime runtime)
            throws Exception {
        this.session = session;
        this.pairing = pairing;
        this.runtime = runtime;
        // GameLoop seeds REDCON's private 48-bit LCG from System.nanoTime at
        // process boot. A network battle must replace that local seed before
        // either committed fortress is instantiated, or equal inputs are not
        // sufficient to produce equal simulations.
        // Before DuelBattleController arms, because OriginalBattleScreen.open
        // forces the duel's pacing on the way through.
        priorFramePacing = mojo.b.u;
        c1.a.j = runtime.simulationSeed();
        // And the generator itself, which is not c1.a.j. Without this the
        // committed seed never reaches the stream crew placement draws from.
        Duel.seedRandomFrom(runtime.simulationSeed());
        System.out.println("   duel flow: arming committed fortresses");
        battle = new DuelBattleController(
            session, runtime.localPlayer(), runtime.standIn(),
            runtime::finishRound);
        runtime.attachBattleConsumer(battle);
        // Shots and hits from the shipped simulation now belong to this duel.
        DuelBattleAuthority.bind(battle, runtime);
        System.out.println("   duel flow: battle controller adopted o3.a1");
        traceTick("armed", runtime.timelineTick());
        removeStockPauseControl();
        updateStatus();
        if (session.phase() == DuelSession.Phase.COMMENCING) {
            showCommencing();
        }
    }

    static void start(DuelSession session, DuelPairingActivity pairing,
                      DuelRuntime runtime)
            throws Exception {
        closeActive();
        active = new DuelBattleFlow(session, pairing, runtime);
    }

    /**
     * Deliver this frame's pointer input with the seated commander's fortress
     * in the position REDCON's battle input reads as the local one.
     *
     * <p>{@code a1.n} and {@code a1.n0} resolve ownership from the static
     * {@code a1.U0}, which the shipped single-player game could hard-code.
     * Swapping the two fortress statics around the engine's own
     * {@code dispatchInput} keeps the entire original selection, multiselect,
     * movement, targeting, cancellation and camera pipeline in play for the far
     * commander rather than substituting a reduced click handler. The pair is
     * restored before anything paints, so the battlefield, HUD and resource
     * bars keep their sides. {@code dispatchInput} returns immediately when no
     * input has arrived since the last dispatch, so the call {@code nextFrame}
     * makes afterwards is a no-op.</p>
     *
     * <p>The assignment is only legal because the recovery step clears the
     * {@code ACC_FINAL} enjarify puts on those two fields; see
     * {@code tools/recovery/definalize.py}.</p>
     */
    static void dispatchOwnedInput() {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed || !isBattleActive()) {
            return;
        }
        dispatchNativeInput(() -> mojo.b.w.dispatchInput());
    }

    /** Shared desktop/Android input ownership, including keyboard shortcuts. */
    static void dispatchNativeInput(Runnable input) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed || !isBattleActive()) {
            input.run();
            return;
        }
        int stage = GameLoop.appStage;
        GameLoop.appStage = GameLoop.APPSTAGE_INPUT;
        try {
            flow.battle.withSeatUi(input);
        } finally {
            // Input may arrive while the native loop is in its paint-only phase.
            // Local pause/speed writes must never release that simulation guard.
            if (!flow.closed) {
                o3.a1.S0 = flow.painting;
                o3.a1.T0 = 1;
            }
            GameLoop.appStage = stage;
        }
    }

    private String tracedState;

    /** Report the battle's committed state whenever it actually changes. */
    private void traceState() {
        String state = session.phase() + " link=" + runtime.link()
            + " commencing=" + (commencing != null)
            + " screen=" + (mojo.b.i.b == null
                ? "none" : mojo.b.i.b.getClass().getSimpleName());
        if (!state.equals(tracedState)) {
            tracedState = state;
            System.err.println("duel battle state " + state
                + " remaining=" + session.commencingTicksRemaining());
        }
    }

    /**
     * Stop advancing the fight while still painting it.
     *
     * <p>Test scaffolding, and deliberately not reachable from the product: a
     * duel has one speed and no pause. The input-ownership check needs the
     * world to hold still between resolving a room and tapping it, because the
     * opposing commander is otherwise busy destroying the room it picked.</p>
     */
    static void holdSimulation(boolean held) {
        simulationHeld = held;
    }

    /** True while the commencement beat is holding both commanders off. */
    static boolean inputLocked() {
        DuelBattleFlow flow = active;
        return flow != null && !flow.closed
            && flow.session.phase() == DuelSession.Phase.COMMENCING;
    }

    /**
     * Product frames traced, so a diagnostic run cannot flood the log.
     *
     * <p>Covers the three-second commencement beat plus the first second of
     * Battle at 60 Hz, which is the whole window in question.</p>
     */
    private static final int TRACE_FRAMES = 260;
    private static int tracedFrames;

    /**
     * Real-time pacing for a pump this code does not own.
     *
     * <p>The desktop shell runs a whole product frame as many times per render
     * as elapsed time calls for, so its barrier is already driven at 60&nbsp;Hz.
     * REDCON's own pump, which the Android hooks are injected into, runs once
     * per rendered frame - so on a 30&nbsp;fps handset it would admit thirty
     * simulation frames a second against the desktop's sixty. This clock is
     * what lets the Android entry point admit the frames real time is owed.</p>
     */
    private static DuelFrameClock pacing;

    /** {@code mojo.b.u} as the base game had it, restored when the duel ends. */
    private int priorFramePacing = -1;

    /**
     * Report {@code o3.c1.o}, REDCON's own battle tick counter.
     *
     * <p>{@code DuelMatch.frame} advances it by exactly one product frame, and
     * the barrier is supposed to be the only thing that calls that. If it
     * climbs between {@code beforeProductFrame} and {@code afterProductFrame}
     * on a frame the barrier did not admit, something outside lockstep is
     * stepping the battle - which is what a desktop client and a handset
     * disagreeing at frame zero, with crew that have walked different
     * distances, would look like.</p>
     */
    private static void traceTick(String what, long frame) {
        if (tracedFrames >= TRACE_FRAMES
                || !DuelIdentity.flag("redcon.lockstepTrace")) {
            return;
        }
        DuelBattleFlow flow = active;
        System.err.println("duel ctick " + what + " c1.o=" + o3.c1.o
            + " u=" + mojo.b.u
            + " frame=" + frame
            + " phase=" + (flow == null ? "none" : flow.session.phase()));
    }

    /**
     * Simulate exactly once before REDCON paints the already-updated world.
     *
     * <p>For a caller that runs a whole product frame per simulation frame -
     * the desktop shell, which consults {@link DuelFrameClock} itself.</p>
     */
    static void beforeProductFrame() {
        pumpProductFrame(1);
    }

    /**
     * Simulate as many frames as real time is owed, then paint once.
     *
     * <p>For a caller whose pump runs once per rendered frame and cannot be
     * made to run twice - REDCON's own loop, which the Android hooks sit
     * inside. The simulation rate has to be 60&nbsp;Hz on every device, so a
     * 30&nbsp;fps handset owes two frames per render.</p>
     */
    static void beforeProductFrames() {
        beforeProductFrames(System.nanoTime());
    }

    /** Explicit clock input lets checks exercise the actual Android adapter. */
    static void beforeProductFrames(long nowNanos) {
        DuelBattleFlow flow = active;
        int steps = 1;
        if (flow != null && !flow.closed
                && flow.session.phase() == DuelSession.Phase.BATTLE) {
            if (pacing == null) {
                pacing = new DuelFrameClock();
            }
            steps = pacing.sample(nowNanos).updates();
        }
        pumpProductFrame(steps);
    }

    private static void pumpProductFrame(int steps) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed) {
            return;
        }
        traceTick("pre", flow.runtime.timelineTick());
        // Committed events land here, on the game thread, before the battle
        // is stepped or read. The peer's orders, corrections, exact result and
        // rematch vote all arrive through this one call.
        flow.runtime.update();
        if (flow.endedByPeer()) {
            return;
        }
        flow.traceState();
        if (flow.commencing != null
                && flow.session.phase() == DuelSession.Phase.BATTLE) {
            // The peer's committed BATTLE clock lands in the update above, so
            // on the device that did not author it the session leaves
            // Commencing before the branch below is ever reached. The beat is
            // dismissed by the phase, not by whose advance crossed it.
            flow.beginBattle();
        }
        if (flow.session.phase() == DuelSession.Phase.COMMENCING) {
            flow.runtime.advance(Duel.TICKS_PER_FRAME);
            if (flow.session.phase() != DuelSession.Phase.BATTLE) {
                if (flow.commencing != null) {
                    flow.commencing.refresh();
                }
                flow.holdEngineStep();
                return;
            }
            // The beat ended on this device's own advance rather than on a
            // clock committed by the peer, so take the same path the peer
            // takes above: adopt the battle screen and then carry on into the
            // barrier, admitting frame zero in this same product frame.
            //
            // Returning here instead handed the freshly adopted screen to
            // REDCON's own update stage with nothing having stepped it, and
            // a1.C() stepped it - one simulation frame that lockstep never
            // admitted, banked on whichever device authored the clock. The two
            // peers then ran one frame apart for the rest of the round: equal
            // inputs, equal seed, and crew that had walked a frame further on
            // one of them by the first digest.
            flow.beginBattle();
        }
        if (flow.session.phase() == DuelSession.Phase.RESULT) {
            // The host's exact result can land a few frames after this device
            // stopped simulating; raising Result here is the same call the
            // local ending makes, only driven by the committed history.
            if (!flow.resultShown) {
                flow.showResult();
            } else {
                flow.refreshResultVotes();
            }
            flow.holdEngineStep();
            return;
        }
        if (flow.session.phase() == DuelSession.Phase.BUILD) {
            // Both rematch votes are committed. The next round opens through
            // the same resume path the local vote uses.
            flow.close();
            DuelBuildFlow.resume(flow.pairing, flow.session, flow.runtime);
            return;
        }
        if (flow.session.phase() != DuelSession.Phase.BATTLE) {
            flow.holdEngineStep();
            return;
        }
        // Every frame, not once at arming: the engine's pacing code can
        // rewrite this whenever it reconsiders the refresh target, and one
        // admitted frame has to be one sixtieth of a second on both peers.
        Duel.ensureFramePacing();
        if (!simulationHeld) {
            for (int step = 0; step < steps; step++) {
                if (step > 0) {
                    // As the desktop shell does between its own steps: drain
                    // the network so a frame that just became available can be
                    // admitted in this same catch-up.
                    flow.runtime.update();
                    if (flow.endedByPeer()) {
                        return;
                    }
                }
                try {
                    if (!flow.runtime.beginBattleFrame()) {
                        // Count held renders, not repeated attempts within one.
                        flow.stalledFrames++;
                        traceTick("held", flow.runtime.timelineTick());
                        break;
                    }
                    flow.stalledFrames = 0;
                    traceTick("admit-before", flow.runtime.timelineTick());
                    flow.battle.frame();
                    flow.runtime.completeBattleFrame();
                    flow.battle.publishDue(flow.runtime);
                    traceTick("admit-after", flow.runtime.timelineTick());
                } catch (RuntimeException | Error failure) {
                    flow.runtime.abortBattleFrame(failure);
                    flow.holdEngineStep();
                    flow.endedByPeer();
                    return;
                }
                if (flow.session.phase() != DuelSession.Phase.BATTLE) {
                    break;
                }
            }
        }
        if (flow.session.phase() == DuelSession.Phase.RESULT) {
            flow.showResult();
            flow.holdEngineStep();
            return;
        }
        flow.updateStatus();
        flow.battle.beginPaint();
        flow.painting = true;
    }

    /**
     * Leave REDCON's own update stage with the battle screen unstepped.
     *
     * <p>The barrier is the only thing allowed to step a duel: one admitted
     * committed frame, one call to {@code a1.J()}. But the engine steps the
     * current screen itself, every frame, from {@code a1.C()} - and it does it
     * after this hook returns. Every frame the barrier admits therefore ends
     * with {@link DuelBattleController#beginPaint()}, which is the product's
     * own pause flag ({@code a1.S0}) and makes that update stage paint without
     * simulating.</p>
     *
     * <p>This is the same assertion for the frames the barrier does not admit:
     * a held frame waiting on the peer, the handover frame, an ended round.
     * Without it a duel would advance on frames lockstep never agreed to, and
     * only on the device that happened to take that branch.</p>
     */
    private void holdEngineStep() {
        if (closed || mojo.b.i.b != battle.match().battle()) {
            return;
        }
        battle.beginPaint();
        painting = true;
    }

    /**
     * Take the duel off screen when the peer is gone, and name the reason.
     *
     * <p>The runtime has already closed the session by the time this reports
     * true, so a lost connection can never leave this device in an apparently
     * live duel against nobody.</p>
     */
    private boolean endedByPeer() {
        if (runtime.link() == DuelRuntime.Link.LIVE
                && session.phase() != DuelSession.Phase.CLOSED) {
            return false;
        }
        System.out.println("   duel flow: duel ended - " + runtime.link());
        close();
        mojo.b.i.g(pairing);
        return true;
    }

    static void afterProductFrame() {
        DuelBattleFlow flow = active;
        if (flow != null && flow.painting) {
            flow.battle.endPaint();
            flow.painting = false;
        }
        if (flow != null && !flow.closed) {
            traceTick("post", flow.runtime.timelineTick());
            tracedFrames++;
        }
    }

    static boolean isBattleActive() {
        return active != null && active.session.phase() == DuelSession.Phase.BATTLE;
    }

    static void activeLeave() {
        if (active != null) {
            active.leave();
        }
    }

    static o3.z activeStatusPlate() {
        return active == null ? null : active.status;
    }

    /** The commander currently holding the controls, for scripted checks. */
    static String activeSeatName() {
        return active == null ? null : active.battle.seat().name;
    }

    /** The live match, for scripted checks that need to resolve picking. */
    static DuelMatch activeMatch() {
        return active == null ? null : active.battle.match();
    }

    /** Called by the patched native aim call sites in a1.n/n0/d0. */
    static boolean emitNativeAim(o3.v1 weapon, o3.v1 target) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed
                || flow.session.phase() != DuelSession.Phase.BATTLE) {
            return false;
        }
        Side side = flow.battle.seat();
        if (side.index != flow.runtime.localPlayer()) {
            return false;
        }
        int weaponId = side.indexOf(weapon);
        int targetId = side.opponent().indexOf(target);
        if (weaponId < 0 || targetId < 0) {
            return false;
        }
        flow.runtime.order(side.index, Order.aim(weaponId, targetId));
        return true;
    }

    /** Called by the patched crew-movement and cancellation calls in a1.g0. */
    static boolean emitNativeCrewMove(o3.g2 crew, o3.v1 room) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed
                || flow.session.phase() != DuelSession.Phase.BATTLE) {
            return false;
        }
        Side side = flow.battle.seat();
        if (side.index != flow.runtime.localPlayer()) {
            return false;
        }
        int crewId = side.indexOfCrew(crew);
        int roomId = side.indexOf(room);
        if (crewId < 0) return false;
        if (roomId >= 0) {
            flow.runtime.order(side.index, Order.moveCrew(crewId, roomId));
        } else {
            roomId = side.opponent().indexOf(room);
            if (roomId < 0) return false;
            flow.runtime.order(side.index, Order.moveCrewToEnemyRoom(crewId, roomId));
        }
        return true;
    }

    static boolean emitNativeRoomPower(o3.v1 room, boolean enabled) {
        return emitNativeRoomSwitch(room, enabled, true);
    }

    static boolean emitNativeRoomFireMode(o3.v1 room, boolean enabled) {
        return emitNativeRoomSwitch(room, enabled, false);
    }

    static boolean emitNativeManualFire(o3.v1 room, boolean requested) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed
                || flow.session.phase() != DuelSession.Phase.BATTLE) return false;
        Side side = flow.battle.seat();
        if (side.index != flow.runtime.localPlayer()) return false;
        int roomId = side.indexOf(room);
        if (roomId < 0) return false;
        flow.runtime.order(side.index, Order.setManualFire(roomId, requested));
        return true;
    }

    /**
     * The seat that may issue an order right now, or null.
     *
     * <p>Every native emitter below asks the same three questions - is there a
     * live battle, is it in the BATTLE phase, and is the seat holding the
     * controls the local player - so they ask them in one place. A null answer
     * means the caller should run the engine's original call instead: that is
     * campaign, hotseat and the pre-battle screens, where no peer exists.</p>
     */
    private static Side emittingSeat() {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed
                || flow.session.phase() != DuelSession.Phase.BATTLE) {
            return null;
        }
        Side side = flow.battle.seat();
        return side.index == flow.runtime.localPlayer() ? side : null;
    }

    private static boolean emit(Side side, Order order) {
        active.runtime.order(side.index, order);
        return true;
    }

    /** a1.c0 event 12: the payload the next airship carries. */
    static boolean emitNativeAmmo(o3.v1 room, int ammoIndex) {
        Side side = emittingSeat();
        if (side == null) return false;
        int roomId = side.indexOf(room);
        if (roomId < 0) return false;
        return emit(side, Order.setAmmo(roomId, ammoIndex));
    }

    /**
     * a1.c0 event 13: the weapon's firing mode.
     *
     * <p>The HUD hands the engine a {@code b2} record, not an index, so the
     * index has to be recovered here - the peer cannot be sent an object
     * reference, and {@code v1.L0} is the same array on both devices because
     * the fortress was committed before the battle started.</p>
     */
    static boolean emitNativeWeaponMode(o3.v1 room, o3.b2 mode) {
        Side side = emittingSeat();
        if (side == null) return false;
        int roomId = side.indexOf(room);
        if (roomId < 0 || room.L0 == null) return false;
        for (int i = 0; i < room.L0.length; i++) {
            if (room.L0[i] == mode) {
                return emit(side, Order.setWeaponMode(roomId, i));
            }
        }
        return false;
    }

    /** a1.c0 event 6: launch, or recall whatever is already flying. */
    static boolean emitNativeAirshipAction() {
        Side side = emittingSeat();
        return side != null && emit(side, Order.airshipAction());
    }

    /** a1.o0: the troop load/unload switch on the airship panel. */
    static boolean emitNativeAirshipCargo(boolean loaded) {
        Side side = emittingSeat();
        return side != null && emit(side, Order.setAirshipCargo(loaded));
    }

    /** a1.e0: the destination the player dropped the airship on. */
    static boolean emitNativeAirshipTarget(o3.v1 room) {
        Side side = emittingSeat();
        if (side == null || room == null) return false;
        int own = side.indexOf(room);
        if (own >= 0) {
            return emit(side, Order.setAirshipTarget(own, false));
        }
        int enemy = side.opponent().indexOf(room);
        if (enemy < 0) return false;
        return emit(side, Order.setAirshipTarget(enemy, true));
    }

    /** a1.c0 events 7 and 9: the scrambler pulse and the manual ICBM launch. */
    static boolean emitNativeForceFire(o3.v1 room) {
        Side side = emittingSeat();
        if (side == null) return false;
        int roomId = side.indexOf(room);
        if (roomId < 0) return false;
        return emit(side, Order.forceFire(roomId));
    }

    /**
     * a1.U: soldiers walking aboard an airship.
     *
     * <p>Boarding changes the native occupant lists. It must be committed;
     * the command names the soldier by a stable ID that survives list changes.</p>
     */
    static boolean emitNativeBoardAirship(o3.g2 crew, o3.a airship) {
        Side side = emittingSeat();
        if (side == null || airship == null) return false;
        int crewId = side.indexOfCrew(crew);
        if (crewId < 0) return false;
        boolean theirs = side.opponent().fortress.w0 == airship;
        if (!theirs && side.fortress.w0 != airship) return false;
        return emit(side, Order.boardAirship(crewId, theirs));
    }

    /** a1.c0 event 8: clandestine conversion of an enemy soldier. */
    static boolean emitNativeClandestine(o3.s0 fortress) {
        Side side = emittingSeat();
        if (side == null || side.fortress != fortress) return false;
        return emit(side, Order.clandestineConvert());
    }

    private static boolean emitNativeRoomSwitch(o3.v1 room, boolean enabled,
                                                 boolean power) {
        DuelBattleFlow flow = active;
        if (flow == null || flow.closed
                || flow.session.phase() != DuelSession.Phase.BATTLE) return false;
        Side side = flow.battle.seat();
        if (side.index != flow.runtime.localPlayer()) return false;
        int roomId = side.indexOf(room);
        if (roomId < 0) return false;
        flow.runtime.order(side.index, power
            ? Order.setRoomPower(roomId, enabled)
            : Order.setRoomFireMode(roomId, enabled));
        return true;
    }

    /** The side currently holding the controls, for scripted checks. */
    static Side activeSeat() {
        return active == null ? null : active.battle.seat();
    }

    /** Current session for product-path regression checks. */
    static DuelSession activeSession() {
        return active == null ? null : active.session;
    }

    /**
     * End the current round through the same Result route as a live battle.
     *
     * <p>This is deliberately package-private test scaffolding. Waiting for
     * five ten-minute clocks would make the product harness impractical; the
     * session still validates and records the real ending vocabulary, and the
     * native Result activity is raised by the production flow.</p>
     */
    static void finishForCheck(DuelSession.RoundResult result) {
        if (!isBattleActive()) {
            throw new IllegalStateException("no live duel battle");
        }
        active.runtime.finishRound(result);
        active.showResult();
    }

    /**
     * Hand the controls to the other commander.
     *
     * <p>No player ever does this: each device drives its own fortress and the
     * opponent's actions arrive from theirs. It exists so the scripted checks
     * can prove the far fortress is genuinely drivable through REDCON's own
     * battle input, which is the thing Phase 04 will be relying on when it
     * applies a peer's orders.</p>
     */
    static void switchSeat() {
        if (isBattleActive()) {
            active.battle.switchSeat();
            active.updateStatus();
        }
    }

    void toggleRematch(int side) {
        if (closed || session.phase() != DuelSession.Phase.RESULT) {
            return;
        }
        if (!runtime.standIn() && side != runtime.localPlayer()) {
            // The opponent votes on their own device. Their row is shown for
            // its state, not as a control this player may press.
            return;
        }
        runtime.setRematchReady(side, !session.rematchReady(side));
        if (session.phase() == DuelSession.Phase.BUILD) {
            close();
            DuelBuildFlow.resume(pairing, session, runtime);
        } else if (mojo.b.i.b instanceof DuelResultActivity) {
            ((DuelResultActivity) mojo.b.i.b).refresh();
        }
    }

    /** True when this device is entitled to cast that commander's vote. */
    boolean localVote(int side) {
        return runtime.standIn() || side == runtime.localPlayer();
    }

    /** Redraw the Result screen when the peer's rematch vote lands. */
    private void refreshResultVotes() {
        boolean near = session.rematchReady(Side.NEAR);
        boolean far = session.rematchReady(Side.FAR);
        if (near == shownNearVote && far == shownFarVote) {
            return;
        }
        shownNearVote = near;
        shownFarVote = far;
        if (mojo.b.i.b instanceof DuelResultActivity) {
            ((DuelResultActivity) mojo.b.i.b).refresh();
        }
    }

    private boolean shownNearVote;
    private boolean shownFarVote;

    /**
     * Paint the ended battlefield's HUD and widget pass.
     *
     * <p>REDCON splits a battle frame in two: {@code a1.C()} records the HUD
     * and widget tree and {@code a1.q()} renders the two fortresses. A modal
     * that covers a live screen has to drive both, exactly as {@code p3.a}
     * does for the campaign map it sits on.</p>
     */
    void paintFrozenBattle() {
        battle.beginPaint();
        battle.match().battle().C();
        battle.endPaint();
    }

    /** Paint the ended fortresses themselves. See {@link #paintFrozenBattle()}. */
    void paintFrozenBattleWorld() {
        battle.beginPaint();
        battle.match().battle().q();
        battle.endPaint();
    }

    void leave() {
        if (!closed && session.phase() != DuelSession.Phase.CLOSED) {
            runtime.leave(battle.seat().index, "left duel");
        }
        close();
        mojo.b.i.g(pairing);
    }

    /**
     * Raise the commencement beat over the armed battle, the same way Result is
     * raised over the ended one: activate and swap directly, so the battle
     * screen stays live underneath and both fortresses are already on show.
     */
    private void showCommencing() {
        status.s();
        commencing = new DuelCommencingActivity(this, session);
        commencing.d = pairing;
        commencing.k = true;
        commencing.A(true);
        mojo.b.i.b = commencing;
    }

    /** Drop the beat and hand the live battle screen back to the players. */
    private void beginBattle() {
        if (commencing != null) {
            commencing.dismiss();
            commencing.A(false);
            commencing = null;
        }
        mojo.b.i.b = battle.match().battle();
        updateStatus();
        System.out.println("   duel flow: commencement complete, battle live");
    }

    private void showResult() {
        seatMarker.s();
        resultShown = true;
        releaseBattleSounds();
        hideBattleHud();
        status.s();
        DuelResultActivity result = new DuelResultActivity(this, session);
        result.d = pairing;
        // REDCON raises an in-battle dialog by activating it and swapping the
        // current activity directly; q3.b.I does exactly this for p3.a. The
        // covered activity stays live, which is what keeps the ended forts on
        // screen. mojo.b.i.g would fade the battle out and tear it down.
        result.k = true;
        result.A(true);
        mojo.b.i.b = result;
    }

    /**
     * Vacate REDCON's pause slot for the duel.
     *
     * <p>{@code a1.V(widget, false)} is the battle screen's own removal call
     * and takes the control out of the widget tree, which is what stops it
     * being tapped. It is not enough on its own: {@code o3.v.n} paints its two
     * corner buttons from direct fields rather than from its child list, so a
     * removed pause control keeps drawing in place. Parking it off the canvas
     * as well leaves the corner genuinely empty instead of covered by another
     * panel. Both changes are undone in {@link #close()} so campaign battles
     * keep their pause control.</p>
     */
    private void removeStockPauseControl() {
        if (stockPause != null) {
            return;
        }
        o3.a1 screen = battle.match().battle();
        i3.q pause = NativeUi.findEvent(screen.u, STOCK_PAUSE_EVENT);
        if (pause == null) {
            return;
        }
        stockPause = pause;
        stockPauseParent = pause.k;
        pauseX = pause.g;
        pauseY = pause.h;
        pauseW = pause.i;
        pauseH = pause.j;
        screen.V(pause, false);
        pause.u(-4096, -4096, pauseW, pauseH);
    }

    /**
     * Take the battle HUD off screen for the result modal.
     *
     * <p>{@code a1.q()} paints the fortresses and then renders the screen's own
     * widget root, so a dialog laid over the battle gets the whole HUD with it -
     * the ability buttons, both resource bars and the room tooltip - however
     * carefully it avoids driving {@code a1.C()}. REDCON's own cutscene shows
     * the fortresses with no HUD at all, and the way it gets there is to clear
     * the visible flag on the covered screen's widgets; {@code o3.b} does
     * exactly this to its own controls before raising one. {@code i3.b.n} skips
     * a child whose flag is clear, so this is the supported way to do it.</p>
     *
     * <p>Only widgets that were visible are recorded, and only those are put
     * back, so nothing the duel or the campaign had already hidden is revived.</p>
     */
    private void hideBattleHud() {
        if (hiddenHud != null) {
            return;
        }
        i3.j root = battle.match().battle().u;
        if (root == null) {
            return;
        }
        i3.q[] hidden = new i3.q[root.t];
        int count = 0;
        for (int index = 0; index < root.t; index++) {
            i3.q child = root.u[index];
            if (child != null && child.f) {
                child.f = false;
                hidden[count++] = child;
            }
        }
        hiddenHud = java.util.Arrays.copyOf(hidden, count);
    }

    /**
     * Put the battle HUD back exactly as it was before the result modal.
     *
     * <p>Defensive rather than load-bearing today: each battle builds a fresh
     * {@code a1.u}, so the flags a result cleared do not in fact reach the next
     * round. A check written against the next round's HUD therefore passes with
     * this call removed, so there is no gate on it - restoring what was changed
     * is simply the correct shape, and it is what makes the pairing above safe
     * if a later path ever does reuse the tree.</p>
     */
    private void showBattleHud() {
        if (hiddenHud == null) {
            return;
        }
        for (i3.q child : hiddenHud) {
            child.f = true;
        }
        hiddenHud = null;
    }

    /** Give the campaign its pause control back exactly as the duel found it. */
    private void restoreStockPauseControl() {
        if (stockPause == null) {
            return;
        }
        if (stockPauseParent != null && stockPause.k == null) {
            stockPauseParent.x(stockPause);
        }
        stockPause.u(pauseX, pauseY, pauseW, pauseH);
        stockPause = null;
        stockPauseParent = null;
    }

    /** Battle HUD widgets hidden while Result is up; null when nothing is. */
    private i3.q[] hiddenHud;

    private void updateStatus() {
        if (session.phase() == DuelSession.Phase.BATTLE) {
            seatMarker.update(battle.match().battle().u, battle.seat().index, System.nanoTime());
        }
        int seconds = Math.max(0, session.matchSeconds()
            - (int) battle.match().elapsedSeconds());
        // o3.z sizes its panel from the wrapped second line, so the longer
        // half of a duel readout belongs there and the identity on the first.
        String title = battle.seat().name.toUpperCase();
        String detail;
        if (stalledFrames >= 6) {
            detail = DuelStrings.of(DuelStrings.WAITING_FOR_OPPONENT);
        } else {
            detail = (seconds / 60) + ":"
                   + String.format("%02d", seconds % 60)
                   + "     " + DuelStrings.of(DuelStrings.SESSION)
                   + " " + session.score(Side.NEAR)
                   + " - " + session.score(Side.FAR);
        }
        status.C(battle.match().battle().u, mojo.b.o / 2, 12, 16, title, detail);
        removeStockPauseControl();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        status.s();
        seatMarker.s();
        if (commencing != null) {
            commencing.dismiss();
            commencing = null;
        }
        releaseBattleSounds();
        showBattleHud();
        restoreStockPauseControl();
        if (painting) {
            battle.endPaint();
            painting = false;
        }
        runtime.detachBattleConsumer(battle);
        DuelBattleAuthority.unbind(battle);
        if (priorFramePacing > 0) {
            // The base game's own value. Leaving the duel's behind would pace
            // a campaign battle at the duel's rate on a device whose refresh
            // is not 60 Hz.
            mojo.b.u = priorFramePacing;
            priorFramePacing = -1;
        }
        pacing = null;
        closed = true;
        if (active == this) {
            active = null;
        }
    }

    /**
     * Give the battle screen REDCON's own release when the duel stops driving it.
     *
     * <p>The two weapon-fire loops are started and stopped inside the battle's
     * per-frame update, from whether a fortress fired this frame. The moment
     * the duel stops running that update - Result is raised over the battle, or
     * the flow closes - whichever loop was playing has nothing left to stop it,
     * and an autocannon carries on firing over the Result screen, the menu and
     * everything after.</p>
     *
     * <p>The campaign never hits this: it leaves a battle through
     * {@code mojo.b.g}, whose transition calls {@code Activity.z(true)} on the
     * outgoing screen, and that calls {@code a1.r()}. A duel raises Result by
     * assigning {@code mojo.b.i.b} directly so the ended fortresses stay on
     * show, so the battle screen underneath is never the one transitioned away
     * and never gets its release. This is the same shape as P5-013.</p>
     *
     * <p>{@code r()} is called rather than {@code z(true)} because the duel
     * reuses the one {@code a1} singleton for every round and never runs the
     * framework transition that would clear the flag {@code z} latches on -
     * after one round {@code z(true)} would return early and release nothing.
     * Stopping a stopped sound is a no-op, so calling it directly is safe on
     * every path.</p>
     */
    private void releaseBattleSounds() {
        if (battle != null) {
            battle.match().battle().r();
        }
    }

    private static void closeActive() {
        if (active != null) {
            active.close();
        }
    }
}
