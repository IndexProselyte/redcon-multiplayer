package redcon.desktop;

import o3.a1;
import o3.f2;
import o3.k0;
import o3.s0;
import o3.v1;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import redcon.duel.AiCommander;
import redcon.duel.Commander;
import redcon.duel.DuelMatch;
import redcon.duel.DuelSession;
import redcon.duel.HumanCommander;
import redcon.duel.Order;
import redcon.duel.Picking;
import redcon.duel.Side;
import redcon.net.DuelBattleEventConsumer;
import redcon.net.DuelEvent;
import redcon.net.DuelRuntime;

/** Drives two human command queues over REDCON's shipped battle activity. */
public final class DuelBattleController implements DuelBattleEventConsumer {

    /**
     * Structure difference below which a correction is not worth applying.
     *
     * <p>Both devices run the same deterministic simulation, so most rooms
     * agree exactly. Routing a rounding-sized difference back through the
     * engine's damage and repair calls would fire destruction and repair cues
     * for nothing.</p>
     */
    private static final float CORRECTION_EPSILON = 0.01f;

    private final DuelSession session;
    private final DuelMatch match;
    private final int localPlayer;
    private final Consumer<DuelSession.RoundResult> resultConsumer;

    /**
     * Shot identity for every ordnance either fortress launches.
     *
     * <p>Both devices simulate both fortresses, so both assign the same id to
     * the same launch: attacker, weapon room, and that weapon's own shot
     * count. Nothing has to be matched up across the wire, and the defender
     * can name the attacker's shot when it reports what the shot did.</p>
     */
    private final Map<k0, Long> shotIds = new IdentityHashMap<>();
    private final Map<Long, Integer> shotSequence = new java.util.HashMap<>();
    /** Recently assigned ids, bounded, for the divergence cross-check. */
    private final java.util.LinkedHashSet<Long> assignedShots =
        new java.util.LinkedHashSet<>();
    /**
     * Shots the peer announced that this device has not launched its own copy
     * of yet.
     *
     * <p>Held rather than counted on arrival: the two simulations do not run
     * in step, so a shot can legitimately be announced before this device's
     * copy of the same weapon has fired. What is left in here once both have
     * run is real divergence.</p>
     */
    private final java.util.LinkedHashSet<Long> unmatchedRemoteShots =
        new java.util.LinkedHashSet<>();

    private Side seat;
    private boolean reported;
    private int firesEmitted;
    private int impactsEmitted;
    private int remoteFires;
    private int remoteImpacts;
    private int correctedRooms;
    private int launches;
    private int damageObserved;
    private int damageUnnamed;
    private int unattributedHits;
    private float unattributedAmount;
    private final k0[] lastLaunched = new k0[2];

    public DuelBattleController(DuelSession session) throws Exception {
        this(session, session::finish);
    }

    public DuelBattleController(
            DuelSession session,
            Consumer<DuelSession.RoundResult> resultConsumer) throws Exception {
        this(session, Side.NEAR, true, resultConsumer);
    }

    /**
     * @param localPlayer the fortress this device drives. It is always
     *                    {@link Side#NEAR} on the single-device routes and is
     *                    whichever side pairing gave this client on a network
     *                    duel.
     * @param standIn     true when the opposing fortress has no device behind
     *                    it and REDCON's own AI plays it. A networked opponent
     *                    is a second pair of hands, so its commander waits for
     *                    committed orders exactly as the local one does.
     */
    public DuelBattleController(
            DuelSession session, int localPlayer, boolean standIn,
            Consumer<DuelSession.RoundResult> resultConsumer) throws Exception {
        if (session == null
                || (session.phase() != DuelSession.Phase.BATTLE
                    && session.phase() != DuelSession.Phase.COMMENCING)) {
            throw new IllegalArgumentException("session must be entering Battle");
        }
        if (resultConsumer == null) {
            throw new IllegalArgumentException("result consumer is null");
        }
        if (localPlayer != Side.NEAR && localPlayer != Side.FAR) {
            throw new IllegalArgumentException("bad local player " + localPlayer);
        }
        this.session = session;
        this.localPlayer = localPlayer;
        this.resultConsumer = resultConsumer;
        a1 battle = OriginalBattleScreen.open(
            session.battleDesign(Side.NEAR), session.battleDesign(Side.FAR));
        // The local fortress is this device's commander. The other one is the
        // opponent: over the network their orders arrive from their device, and
        // on the development route the game's own AI plays them. Either way it
        // is not a second pair of hands on this machine.
        Commander near = localPlayer == Side.NEAR || !standIn
            ? new HumanCommander(session.playerName(Side.NEAR))
            : new AiCommander(o3.a1.W0, session.playerName(Side.NEAR));
        Commander far = localPlayer == Side.FAR || !standIn
            ? new HumanCommander(session.playerName(Side.FAR))
            : new AiCommander(o3.a1.X0, session.playerName(Side.FAR));
        match = DuelMatch.adopt(battle, near, far, session.matchSeconds());
        seat = localPlayer == Side.NEAR ? match.near() : match.far();
        seat.takeSeat();
        configureSeatHud();
    }

    /** Simulate one normal-speed, unpaused frame and report a terminal result. */
    public void frame() {
        if (match.result() != null) {
            reportResult();
            return;
        }
        match.frame();
        if (DuelIdentity.flag("redcon.lockstepTrace")) {
            System.err.println("duel frame " + (match.frames() - 1)
                + " tick=" + o3.c1.o + " delta=" + o3.c1.p
                + " lcg=" + Long.toHexString(c1.a.j)
                + " mwc=" + Integer.toHexString(c1.a.t) + ":" + Integer.toHexString(c1.a.u)
                + " " + DuelStateDigest.components(match)
                + " " + DuelStateDigest.roomFields(match)
                + " " + DuelStateDigest.entityCensus(match).trim());
        }
        try {
            withSeatUi(() -> {
                match.battle().H0();
                match.battle().E0();
            });
        } catch (Throwable stalePresentation) {
            // Selection widgets may be absent in a headless check. Both-side
            // resource bars update in o3.v.w() during the normal paint path.
        }
        reportResult();
    }

    /**
     * Every field the state digest covers, split by part and printed raw.
     *
     * <p>The three parts are hashed independently so a report says whether the
     * forts, the crew or the ordnance in flight is what moved, and the crew
     * fields are printed with their raw float bits as well as their decimal
     * values - a one-ulp difference between two runtimes is invisible in
     * decimal and is exactly the kind of difference a desync is made of.</p>
     */
    @Override
    public String stateReport() {
        StringBuilder out = new StringBuilder();
        out.append("simulation frame=").append(match.frames() - 1)
           .append(" ticks=").append(o3.c1.o).append(" delta=").append(o3.c1.p)
           .append(" camera=").append(a1.d1).append('\n');
        // The two generators, because which one moved says what kind of
        // difference this is. Equal streams with unequal shells is the two
        // runtimes' arithmetic; unequal streams is a draw one device made and
        // the other did not.
        out.append("lcg=").append(Long.toHexString(c1.a.j))
           .append(" mwc=").append(Integer.toHexString(c1.a.t))
           .append(':').append(Integer.toHexString(c1.a.u))
           .append(" entities=").append(Duel.entities(match.battle()))
           .append("\n");
        out.append("digest ")
           .append(Long.toHexString(DuelStateDigest.fortRoster(match)))
           .append("\n");
        out.append("parts ").append(DuelStateDigest.components(match))
           .append("\n");
        out.append("rooms ").append(DuelStateDigest.roomFields(match)).append("\n");
        out.append(DuelStateDigest.detail(match));
        out.append(DuelStateDigest.crewFields(match));
        out.append(DuelStateDigest.ordnanceFields(match));
        out.append(DuelStateDigest.entityCensus(match));
        return out.toString();
    }

    /** Stop the original activity simulating a second time while it paints. */
    public void beginPaint() {
        a1.S0 = true;
    }

    public void endPaint() {
        a1.S0 = false;
    }

    public void switchSeat() {
        if (match.result() != null) {
            return;
        }
        // Both commanders play through REDCON's own battle input, so the
        // engine - not Side - holds the live selection. Take it back before
        // the incoming commander restores theirs over the top of it.
        seat.captureSeat();
        seat = seat.opponent();
        seat.takeSeat();
        configureSeatHud();
    }

    /** Temporarily expose the local fort to stock UI code; never step here. */
    void withSeatUi(Runnable action) {
        s0 near = a1.U0;
        s0 far = a1.V0;
        a1.U0 = seat.fortress;
        a1.V0 = seat.opponent().fortress;
        try {
            action.run();
        } finally {
            a1.U0 = near;
            a1.V0 = far;
        }
    }

    /** The stock screen caches the near fort's special rooms when it opens. */
    private void configureSeatHud() {
        a1 ui = match.battle();
        // The native soldier strip caches its owner once during campaign setup.
        // Rebind it alongside the weapon/ability controls when this seat changes.
        ui.E.v = seat.fortress.y;
        ui.E.w();
        ui.G0 = seat.fortress.I(15);
        ui.H0 = seat.fortress.I(8);
        ui.I0 = seat.fortress.I(3);
        ui.J0 = seat.fortress.I(4);
        o3.v panel = ui.v;
        for (int i = 0; i < panel.M; i++) panel.L[i].s();
        panel.M = 0;
        ui.M(panel, ui.v0, 5, 411, 410, 412, f3.g.a(26, 0));
        if (ui.I0 != null) ui.M(panel, ui.x0, 7, 429, 428, 430, f3.g.a(33, 0));
        if (ui.H0 != null) ui.M(panel, ui.w0, 6, 397, 396, 401, f3.g.a(24, 0));
        if (ui.J0 != null) ui.M(panel, ui.y0, 8, 404, 403, 405, f3.g.a(28, 0));
        if (ui.G0 != null) ui.M(panel, ui.z0, 9, 418, 417, 419, f3.g.a(29, 0));
        panel.F();
        panel.C(ui.v0, false);
        withSeatUi(ui::E0);
    }

    /**
     * Translate a tap into duel orders.
     *
     * <p>The shipped product path no longer uses this: both commanders play
     * through REDCON's own battle input, with {@code DuelBattleFlow} deciding
     * which fortress the engine reads as the local one. This remains for the
     * order-level harnesses ({@code HotseatSessionWindow}) and as the shape
     * Phase 04 will need when it has to put a commander's actions on the
     * wire.</p>
     */
    public void leftClick(int x, int y) {
        if (match.result() != null) {
            return;
        }
        // REDCON is touch-first: crew selection is a normal primary tap, not a
        // desktop right-click. Resolve the small soldier hit area before the
        // containing room so selecting a soldier does not select the room
        // underneath it instead. Repeated taps build the native multiselection.
        o3.g2 crew = Picking.crewNear(
            seat, a1.B0(x), a1.C0(x, y));
        if (crew != null) {
            handOf(seat).give(Order.selectCrew(seat.indexOfCrew(crew)));
            return;
        }
        Picking.Pick pick = Picking.atScreen(match, x, y);
        HumanCommander hand = handOf(seat);
        if (!pick.hit()) {
            hand.give(Order.clearSelection());
        } else if (pick.belongsTo(seat)) {
            if (!seat.selectedCrew().isEmpty()) {
                hand.give(Order.moveSelectedCrew(pick.roomIndex));
            } else {
                hand.give(Order.selectRoom(pick.roomIndex));
            }
        } else {
            v1 selected = seat.selectedRoom();
            if (selected != null && selected.u0 == seat.fortress) {
                hand.give(Order.aim(seat.indexOf(selected), pick.roomIndex));
            } else {
                hand.give(Order.selectEnemyRoom(pick.roomIndex));
            }
        }
    }

    public void rightClick(int x, int y) {
        // Deliberately unused. All gameplay interaction follows REDCON's
        // original single-pointer/left-click model.
    }

    public Side seat() {
        return seat;
    }

    public DuelMatch match() {
        return match;
    }

    /** The side this device is entitled to author events for. */
    public Side localSide() {
        return localPlayer == Side.NEAR ? match.near() : match.far();
    }

    // ------------------------------------------------------- native authorship

    /**
     * Record a launch and, when it is this device's own, put it on the wire.
     *
     * <p>Called from the patched ordnance-launch call site inside the room's
     * own fire method, so a shot is identified where REDCON creates it rather
     * than being reconstructed from a later observation.</p>
     */
    public void launched(k0 ordnance, DuelRuntime runtime) {
        if (ordnance == null || ordnance.x == null) {
            return;
        }
        Side attacker = sideOf(ordnance.x.u());
        if (attacker == null) {
            return;
        }
        int weaponRoom = attacker.indexOf(roomOf(ordnance.x));
        if (weaponRoom < 0) {
            return;
        }
        launches++;
        lastLaunched[attacker.index] = ordnance;
        long shotId = nextShotId(attacker.index, weaponRoom);
        shotIds.put(ordnance, Long.valueOf(shotId));
        remember(shotId);
        unmatchedRemoteShots.remove(Long.valueOf(shotId));
        if (attacker.index != localPlayer || runtime == null) {
            return;
        }
        // o3.p0 is the ordnance carrying a damage record; other actors the
        // same launch path creates (tracer marks) have no ordnance type.
        int ordnanceType = -1;
        if (ordnance instanceof o3.p0 && ((o3.p0) ordnance).Q != null) {
            ordnanceType = ((o3.p0) ordnance).Q.d;
        }
        runtime.fire(localPlayer, shotId, weaponRoom, ordnanceType,
                     ordnance.C, ordnance.D);
        firesEmitted++;
    }

    /**
     * Report damage this device's own fortress just took.
     *
     * <p>The defender is the authority on what a shot did, so only the device
     * whose room was hit authors the record. The attacker's copy of the same
     * shot produces nothing.</p>
     */
    public void damaged(f2 target, float amount, int damageType,
                        k0 ordnance, DuelRuntime runtime) {
        if (runtime == null || target == null || amount == 0.0f) {
            return;
        }
        damageObserved++;
        v1 room = roomOf(target);
        if (room == null) {
            return;
        }
        Side defender = sideOf(room.u0);
        if (defender == null || defender.index != localPlayer) {
            return;
        }
        int roomId = defender.indexOf(room);
        Long shotId = ordnance == null ? null : shotIds.get(ordnance);
        if (roomId < 0 || shotId == null) {
            damageUnnamed++;
            return;
        }
        runtime.impact(localPlayer, shotId.longValue(), roomId, amount,
                       new DuelEvent.ImpactEffect(
                           damageType, 1.0f, 0.0f, 0.0f, 0.0f, room.M0, 0));
        impactsEmitted++;
    }

    /**
     * Record damage to our own fortress that no call site could name.
     *
     * <p>{@code FORT_SYNC} is what carries this to the peer. Counting it keeps
     * the gap visible: a duel in which most damage arrives this way is one
     * whose corrections are doing the work.</p>
     */
    public void unattributedDamage(f2 target, float amount, DuelRuntime runtime) {
        v1 room = roomOf(target);
        if (room == null || amount == 0.0f) {
            return;
        }
        Side defender = sideOf(room.u0);
        if (defender == null || defender.index != localPlayer) {
            return;
        }
        unattributedHits++;
        unattributedAmount += amount;
    }

    /** Send the periodic correction for this device's own fortress. */
    public void publishDue(DuelRuntime runtime) {
        if (runtime == null || !runtime.correctionDue()) {
            return;
        }
        if (DuelIdentity.flag("redcon.lockstepTrace")) {
            // G8b diagnostic: the digest says the two simulations differ, but
            // not why. The shared LCG is the first suspect, because anything
            // that draws from it outside the simulation - a muzzle flash on a
            // device that renders, say - moves every later draw.
            System.err.println("duel trace tick=" + runtime.timelineTick()
                + " lcg=" + Long.toHexString(c1.a.j)
                + " entities=" + Duel.entities(match.battle())
                + " digest=" + Long.toHexString(
                    DuelStateDigest.fortRoster(match)));
            System.err.println("duel parts " + DuelStateDigest.components(match));
            for (String line : DuelStateDigest.detail(match).split("\n")) {
                if (!line.isEmpty()) System.err.println("duel detail " + line);
            }
            for (String line : DuelStateDigest.crewFields(match).split("\n")) {
                if (!line.isEmpty()) System.err.println("duel crew " + line);
            }
        }
        runtime.checkpointDigest(
            runtime.localPlayer(), DuelStateDigest.fortRoster(match));
        if (!DuelIdentity.flag("redcon.lockstepCorrections")) {
            return;
        }
        Side own = localSide();
        s0 fortress = own.fortress;
        int count = fortress.Q;
        int[] roomIds = new int[count];
        float[] hitPoints = new float[count];
        int[] states = new int[count];
        int at = 0;
        for (int index = 0; index < count; index++) {
            v1 room = fortress.R[index];
            if (room == null) {
                continue;
            }
            roomIds[at] = index;
            hitPoints[at] = room.X0;
            states[at] = room.M0;
            at++;
        }
        runtime.synchronizeFort(localPlayer,
            java.util.Arrays.copyOf(roomIds, at),
            java.util.Arrays.copyOf(hitPoints, at),
            java.util.Arrays.copyOf(states, at));
    }

    // -------------------------------------------------- committed application

    @Override
    public void applyOrder(DuelEvent.OrderIssued issued) {
        Side side = issued.player == Side.NEAR ? match.near() : match.far();
        // Refusal is a normal simulation outcome (route blocked, weapon no
        // longer available, stale target). The event was still consumed.
        match.applyOrder(side, issued.order());
    }

    /**
     * Cross-check the attacker's own account of a shot.
     *
     * <p>This device already simulated the shot: both clients run both
     * fortresses. What the attacker's record adds is agreement - a shot id
     * this device never assigned means the two simulations have diverged, and
     * that is worth counting rather than silently applying a second shot.</p>
     */
    @Override
    public void applyFire(DuelEvent.Fire fire) {
        if (fire.attacker == localPlayer) {
            return;
        }
        remoteFires++;
        if (!assignedShots.contains(Long.valueOf(fire.shotId))) {
            unmatchedRemoteShots.add(Long.valueOf(fire.shotId));
            while (unmatchedRemoteShots.size() > 4096) {
                java.util.Iterator<Long> oldest =
                    unmatchedRemoteShots.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * Record the defender's account of what a shot did.
     *
     * <p>Damage is not re-applied here. This device's own copy of the shot
     * already resolved against its own copy of the defender's fortress, and
     * adding the defender's number on top would count it twice. Structure
     * authority is {@code FORT_SYNC}, which carries the defender's resulting
     * hit points rather than a difference.</p>
     */
    @Override
    public void applyImpact(DuelEvent.Impact impact) {
        if (impact.defender == localPlayer) {
            return;
        }
        remoteImpacts++;
    }

    /**
     * Bring this device's copy of the peer's fortress back onto their numbers.
     *
     * <p>The correction is applied through REDCON's own {@code v1.E} and
     * {@code v1.p0}, not by assigning structure, so a room that the defender
     * says is destroyed runs the engine's destruction path here as well -
     * status flags, crew, the wreck cue and the fortress-level effects.</p>
     */
    @Override
    public void applyFortSync(DuelEvent.FortSync correction) {
        if (correction.player == localPlayer) {
            return;
        }
        Side owner = correction.player == Side.NEAR ? match.near() : match.far();
        for (int index = 0; index < correction.size(); index++) {
            v1 room = owner.room(correction.roomId(index));
            if (room == null) {
                continue;
            }
            float authoritative = correction.hitPoints(index);
            float drift = room.X0 - authoritative;
            if (drift > CORRECTION_EPSILON) {
                room.E(drift);
                correctedRooms++;
            } else if (drift < -CORRECTION_EPSILON) {
                room.p0(-drift);
                correctedRooms++;
            }
        }
    }

    // ------------------------------------------------------------ diagnostics

    public int firesEmitted() { return firesEmitted; }
    public int impactsEmitted() { return impactsEmitted; }
    public int remoteFires() { return remoteFires; }
    public int remoteImpacts() { return remoteImpacts; }
    /** Peer shots this device's own simulation has still not produced. */
    public int unknownRemoteShots() { return unmatchedRemoteShots.size(); }
    public int correctedRooms() { return correctedRooms; }

    /** Every ordnance either fortress launched, for divergence diagnostics. */
    public int launches() { return launches; }

    /** Every hit the patched call site reported, on either fortress. */
    public int damageObserved() { return damageObserved; }

    /** Hits on this fortress the shot table could not name. */
    public int damageUnnamed() { return damageUnnamed; }

    /** Blast and continuous-weapon damage to this fortress, which FORT_SYNC carries. */
    public int unattributedHits() { return unattributedHits; }

    public float unattributedAmount() { return unattributedAmount; }

    /**
     * The most recent shot this side launched, or null before its first.
     *
     * <p>Diagnostic, and the seam {@code DuelNativeAuthorityCheck} uses to
     * resolve a real shot against a real room instead of waiting for two
     * artillery pieces to hit each other by chance.</p>
     */
    public k0 lastLaunched(int player) {
        return player == Side.NEAR || player == Side.FAR
            ? lastLaunched[player] : null;
    }

    // ----------------------------------------------------------------- inner

    /** Keep the cross-check set bounded; a long battle launches thousands. */
    private void remember(long shotId) {
        assignedShots.add(Long.valueOf(shotId));
        if (assignedShots.size() > 4096) {
            java.util.Iterator<Long> oldest = assignedShots.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private long nextShotId(int attacker, int weaponRoom) {
        long weapon = ((long) attacker << 56) | ((long) weaponRoom << 40);
        Integer previous = shotSequence.get(Long.valueOf(weapon));
        int next = previous == null ? 0 : previous.intValue() + 1;
        shotSequence.put(Long.valueOf(weapon), Integer.valueOf(next));
        return weapon | (next & 0xffffffffL);
    }

    private Side sideOf(s0 fortress) {
        if (fortress == null) {
            return null;
        }
        if (fortress == match.near().fortress) {
            return match.near();
        }
        if (fortress == match.far().fortress) {
            return match.far();
        }
        return null;
    }

    private static v1 roomOf(f2 entity) {
        return entity instanceof v1 ? (v1) entity : null;
    }

    private void reportResult() {
        if (!reported && match.result() != null) {
            DuelMatch.Result result = match.result();
            int winner = result.winner == null ? -1 : result.winner.index;
            resultConsumer.accept(new DuelSession.RoundResult(
                winner, result.ending, result.seconds));
            reported = true;
        }
    }

    private HumanCommander handOf(Side side) {
        Commander commander = side == match.near()
            ? match.nearCommander() : match.farCommander();
        return (HumanCommander) commander;
    }
}
