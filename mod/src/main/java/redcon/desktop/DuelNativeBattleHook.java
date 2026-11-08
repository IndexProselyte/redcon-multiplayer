package redcon.desktop;

import c1.a;
import o3.c1;
import o3.f2;
import o3.g2;
import o3.i0;
import o3.k0;
import o3.v1;

/** Static targets used by the tiny native smali call-site substitutions. */
public final class DuelNativeBattleHook {
    private static v1 pendingWeaponModeRoom;
    private static o3.b2 pendingWeaponModeHint;

    public static void buildRows(o3.b editor, o3.h list, o3.m1[] families) {
        if (!DuelBuildProfile.owns(editor)) { editor.R0(list, families); return; }
        list.F.z();
        list.z = false;
        for (o3.m1 item : families) {
            while (item.m != null) item = item.m;
            item.p = false;
            list.E(editor.Q0(item));
        }
    }

    public static void buildRow(o3.c row, boolean recount) {
        row.A(recount);
        if (!DuelBuildProfile.owns(o3.b.z2)) return;
        DuelBuildProfile.activeProfile().refreshBalance();
        int credits = a.s(o3.b.z2.C1.f);
        if (row instanceof o3.p) {
            o3.p room = (o3.p) row;
            int price = room.x.i ^ room.x.q;
            room.O = false; room.S = true; room.T = price <= credits;
            room.A.o(0, "$" + price);
            room.A.l(room.T ? -1 : 0xffff7777);
            room.B.o(0, "PLACED " + room.R);
            room.B.l(0xffaaaaaa);
        } else if (row instanceof o3.s) {
            o3.s tower = (o3.s) row;
            int price = tower.x.h ^ tower.x.j;
            tower.O = false; tower.S = true; tower.T = price <= credits;
            tower.A.o(0, "$" + price);
            tower.A.l(tower.T ? -1 : 0xffff7777);
            tower.B.o(0, "PLACED " + tower.R);
            tower.B.l(0xffaaaaaa);
        }
    }

    public static void placeBuildRoom(o3.b editor, o3.e drag) {
        editor.U0(drag);
        DuelBuildProfile.refreshEditor(editor);
    }

    public static void placeBuildTower(o3.b editor, o3.e drag) {
        editor.V0(drag);
        DuelBuildProfile.refreshEditor(editor);
    }

    public static void buildDetails(o3.d panel, boolean layout) {
        panel.D(layout);
        if (!DuelBuildProfile.owns(o3.b.z2)) return;
        // Inventory purchases/upgrades have no meaning in a loadout-priced duel.
        if (panel instanceof o3.n && ((o3.n) panel).M != null) ((o3.n) panel).M.s();
        if (panel instanceof o3.q && ((o3.q) panel).L != null) ((o3.q) panel).L.s();
    }

    /** Pause is a campaign UI operation; in a duel S0 is also the render guard. */
    public static void pause(o3.a1 battle, boolean paused) {
        if (!DuelBattleFlow.isBattleActive()) battle.j0(paused);
    }

    public static void speed(o3.a1 battle, int speed) {
        if (!DuelBattleFlow.isBattleActive()) battle.X(speed);
    }

    /** Rectangle geometry follows the physical pane; ownership follows the local seat. */
    public static void releaseSelection(o3.a1 battle, int x, int y, boolean mouse) {
        if (!DuelBattleFlow.isBattleActive() || battle.b0 != 3) {
            battle.n0(x, y, mouse);
            return;
        }
        redcon.duel.DuelMatch match = DuelBattleFlow.activeMatch();
        redcon.duel.Side owner = DuelBattleFlow.activeSeat();
        boolean left = battle.T < o3.a1.a1 / 2;
        o3.s0 physicalFort = left ? match.near().fortress : match.far().fortress;
        int minX = Math.max(battle.d0, left ? 0 : o3.a1.a1 / 2);
        int maxX = Math.min(battle.f0, left ? o3.a1.a1 / 2 - 1 : o3.a1.a1 - 1);
        int minWorldX = o3.a1.B0(minX), maxWorldX = o3.a1.B0(maxX);
        int minWorldY = o3.a1.C0(minX, battle.e0), maxWorldY = o3.a1.C0(minX, battle.g0);
        battle.b0 = 0;
        battle.Q();
        if (minX > maxX) return;
        // X is the owned roster, including boarding troops; d0/e0 are location lists.
        for (int n = 0; n < owner.crewCount(); n++) {
            g2 crew = owner.crew(n);
            if (crew == null || !crew.A() || crew.y0 != owner.fortress
                    || crew.F0 == null || crew.F0.u0 != physicalFort) continue;
            if (minWorldX <= crew.B0 + 8 && maxWorldX >= crew.B0 - 8
                    && minWorldY <= crew.C0 && maxWorldY >= crew.C0 - 35) battle.p0(crew);
        }
    }

    // q3.s owns particles, not collision sprites. Its startup writes a.j
    // directly and k3.a's update also draws from it. Both must be isolated,
    // including calls made inside the simulation's entity update.
    private static long visualLcg = 0x5deece66dL;

    // Music can choose a track from an audio callback. Use an independent,
    // thread-safe generator instead of temporarily swapping the global LCG.
    private static final java.util.Random audioRandom = new java.util.Random();
    private static final java.util.Random ambientRandom = new java.util.Random();

    /** c0 has no collision bounds; its sprite variant never drives combat. */
    public static int ambientVariant(int maximum) {
        return GameContext.isDuel()
            ? (ambientRandom.nextInt() >>> 1) % (maximum + 1) : a.O(maximum);
    }

    public static int soundBits(int bits) {
        return GameContext.isDuel()
            ? audioRandom.nextInt() >>> (32 - bits) : a.N(bits);
    }

    public static int soundVariant(int maximum) {
        return GameContext.isDuel()
            ? soundBits(31) % (maximum + 1) : a.O(maximum);
    }

    public static void startParticles(q3.s particles, int ticks, int repeats) {
        if (!GameContext.isDuel()) {
            particles.k(ticks, repeats);
            return;
        }
        long simulationLcg = a.j;
        a.j = visualLcg;
        try {
            particles.k(ticks, repeats);
        } finally {
            visualLcg = a.j;
            a.j = simulationLcg;
        }
    }

    public static boolean updateParticles(q3.s particles, int ticks) {
        if (!GameContext.isDuel()) return particles.g(ticks);
        long simulationLcg = a.j;
        a.j = visualLcg;
        try {
            return particles.g(ticks);
        } finally {
            visualLcg = a.j;
            a.j = simulationLcg;
        }
    }

    /** Emit first; only campaign/unbound input falls back to the stock call. */
    public static void aim(v1 weapon, v1 target) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAim(weapon, target);
        } else {
            weapon.s0(target);
        }
    }

    /** Distinguish repeated room selection from a rapid destination tap. */
    public static boolean selectCrewOrMove(o3.a1 battle, int worldX, int count, v1 room) {
        if (DuelBattleFlow.isBattleActive() && battle.F0.a > 0 && room != null) {
            g2 selected = (g2) ((Object[]) battle.F0.b)[0];
            // Stock touch input treats every rapid tap as multi-selection,
            // even in a different room. Keep multi-selection within a room;
            // a different reachable room is a move through the existing hook.
            if (selected.F0 != null && selected.F0 != room
                    && selected.F0.y0 == room.y0) {
                boolean moved = battle.g0(battle.F0, room);
                if (moved) {
                    battle.N(room);
                    battle.Q();
                }
                return moved;
            }
        }
        return battle.q0(worldX, count, room);
    }

    /** Preserve g0's boolean feedback while routing the mutation via ORDER. */
    public static boolean moveCrew(g2 crew, v1 room) {
        if (DuelBattleFlow.isBattleActive()) return DuelBattleFlow.emitNativeCrewMove(crew, room);
        return crew.Q(room);
    }

    /** g0's same-room branch is a movement cancellation, not a local UI edit. */
    public static void cancelCrew(g2 crew) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeCrewMove(crew, crew.F0);
        } else {
            crew.C();
        }
    }

    /** Power and manual-fire HUD switches must commit before changing a room. */
    public static void setRoomPower(v1 room, boolean enabled) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeRoomPower(room, enabled);
        } else {
            room.q0(enabled);
        }
    }

    public static void setRoomFireMode(v1 room, boolean enabled) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeRoomFireMode(room, enabled);
        } else {
            room.r0(enabled);
        }
    }

    /** The native HUD and B hotkey both write this bit with putfield. */
    public static void setManualFireBits(v1 room, int proposedFlags) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeManualFire(room, (proposedFlags & 16384) != 0);
        } else {
            room.M0 = proposedFlags;
        }
    }

    // The controls below gate on the battle, not on whether the emit was
    // accepted, which is the same rule the power switch already follows. A
    // refused emit means the pressing seat is not the local player - a
    // stand-in, or a seat mid-handover - and in a live duel that is precisely
    // when a local mutation would be a divergence rather than a fallback.
    // Campaign and the pre-battle screens still run the original call.

    /**
     * Weapon payload and firing mode - REDCON's two "ammo" controls.
     *
     * <p>Neither is cosmetic. {@code v1.i1} decides whether Fleet Command
     * launches a troop transport or an EMP bomber, and {@code v1.I} moves the
     * room's reload bank between the two modes' pools as it switches, so it
     * decides when the weapon next fires. Both were direct writes.</p>
     */
    public static void setAmmoIndex(v1 room, int ammoIndex) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAmmo(room, ammoIndex);
        } else {
            room.i1 = ammoIndex;
        }
    }

    public static synchronized void setWeaponMode(v1 room, o3.b2 mode) {
        if (DuelBattleFlow.isBattleActive()) {
            if (DuelBattleFlow.emitNativeWeaponMode(room, mode)) {
                // The native handler displays its tooltip before the queued
                // lockstep order is committed. Remember the pressed mode so
                // that UI-only read does not show the previously active
                // ammunition's description.
                pendingWeaponModeRoom = room;
                pendingWeaponModeHint = mode;
            } else {
                pendingWeaponModeRoom = null;
                pendingWeaponModeHint = null;
            }
        } else {
            pendingWeaponModeRoom = null;
            pendingWeaponModeHint = null;
            room.I(mode);
        }
    }

    /** UI-only mode read used by the native ammunition tooltip. */
    public static synchronized o3.b2 weaponModeHint(v1 room) {
        o3.b2 result = room.h2;
        if (room == pendingWeaponModeRoom && pendingWeaponModeHint != null) {
            result = pendingWeaponModeHint;
        }
        pendingWeaponModeRoom = null;
        pendingWeaponModeHint = null;
        return result;
    }

    /**
     * Airship launch. The engine's return value is discarded at this call site,
     * so returning null while the order is in flight changes nothing local.
     */
    public static o3.a airshipLaunch(o3.s0 fortress) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAirshipAction();
            return null;
        }
        return fortress.E();
    }

    /**
     * Airship recall. The HUD spells recall as two calls - {@code A} then
     * {@code C(false)} - and the committed {@code AIRSHIP_ACTION} performs
     * both, so the {@code C} that follows this one is suppressed rather than
     * turned into a second order.
     */
    public static void airshipRecall(o3.a airship) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAirshipAction();
        } else {
            airship.A();
        }
    }

    /** See {@link #airshipRecall}: the tail of the recall, not a cargo change. */
    public static void airshipRecallCargo(o3.a airship, boolean loaded) {
        if (!DuelBattleFlow.isBattleActive()) {
            airship.C(loaded);
        }
    }

    /** a1.o0's own troop switch, which is a cargo change. */
    public static void setAirshipCargo(o3.a airship, boolean loaded) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAirshipCargo(loaded);
        } else {
            airship.C(loaded);
        }
    }

    public static void setAirshipTarget(o3.a airship, v1 room) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeAirshipTarget(room);
        } else {
            airship.B(room);
        }
    }

    /**
     * Fire a room on demand: the scrambler relay and the manual ICBM launch.
     *
     * <p>Both HUD buttons reach {@code v1.P} and neither waits for a reload, so
     * both change the world the instant they are pressed. The scrambler is the
     * worse of the two: its pulse turns every guided missile in flight around,
     * which is a change to ordnance the other device also owns.</p>
     */
    public static void forceFire(v1 room) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeForceFire(room);
        } else {
            room.P();
        }
    }

    /**
     * The scrambler's charge, spent by the HUD in the same breath as the pulse.
     *
     * <p>Committed {@code FORCE_FIRE} spends it, so writing it here as well
     * would leave the clicking device a few frames ahead on the recharge.</p>
     */
    public static void setAbilityCharge(o3.s0 fortress, float charge) {
        if (!DuelBattleFlow.isBattleActive()) {
            fortress.E0 = charge;
        }
    }

    /**
     * Soldiers boarding an airship, from a1.U.
     *
     * <p>Not one of the HUD buttons: this is dragging a selection onto the
     * airship. It is the worst of the group, because a soldier changing
     * fortress swap-removes them from {@code s0.d0}, and that list is what
     * every crew-bearing order indexes into.</p>
     */
    public static void boardAirship(g2 crew, o3.a airship) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeBoardAirship(crew, airship);
        } else {
            crew.O(airship);
        }
    }

    /** Clandestine conversion, which reaches across and takes enemy crew. */
    public static void clandestineConvert(o3.s0 fortress) {
        if (DuelBattleFlow.isBattleActive()) {
            DuelBattleFlow.emitNativeClandestine(fortress);
        } else {
            fortress.A();
        }
    }

    // The playfield the duel measures against, in place of the canvas. The
    // reasoning, the measured canvas sizes and the choice of values are all in
    // DuelCombatGeometry; these three exist because the call-site patcher
    // rewrites a getstatic into a call, and a getstatic has no receiver.

    public static int combatWidth() {
        return DuelCombatGeometry.width();
    }

    public static int combatHeight() {
        return DuelCombatGeometry.height();
    }

    public static float combatCeiling() {
        return DuelCombatGeometry.ceiling();
    }

    /**
     * Observe every ordnance launch, then let the engine keep the actor.
     *
     * <p>Unlike aim and crew movement, a launch is not a player action: it is
     * the simulation's own consequence of an order that already crossed the
     * committed boundary. So the original call runs first and the shot is
     * identified afterwards - there is no local mutation being hidden from the
     * peer, only a fact being reported.</p>
     */
    public static void launch(c1 battle, i0 actor) {
        battle.D(actor);
        if (actor instanceof k0) {
            DuelBattleAuthority.launched((k0) actor);
        }
    }

    /**
     * Let REDCON resolve the hit, then hold what it did until the shot is named.
     *
     * <p>The defender is the authority on damage to its own rooms, so this runs
     * the shipped damage routing untouched and records the outcome. The peer
     * receives the record, not a second application.</p>
     *
     * <p>The record is held rather than reported because this call site does
     * not know which shot it belongs to: {@code p0.k} damages the room it hit
     * and then retires itself two statements later, and the retirement is the
     * call that carries the ordnance. A rewrite that passed both would have to
     * insert an instruction and move every offset after it in the method, which
     * the desktop jar's call-site patcher deliberately will not do. Both calls
     * happen on the game thread, in that order, inside one method.</p>
     */
    public static void damage(f2 target, float amount, int damageType) {
        target.t(amount, damageType);
        pendingTarget = target;
        pendingAmount = amount;
        pendingType = damageType;
    }

    /**
     * Name the shot that caused the damage above, then retire it as usual.
     *
     * <p>Ordnance that reaches the end of {@code p0.k} without damaging
     * anything - a shot the defender's geometry swallowed - clears the record
     * and reports nothing.</p>
     */
    public static void resolve(c1 battle, i0 actor) {
        if (actor instanceof k0) {
            resolvedShots++;
        }
        f2 target = pendingTarget;
        float amount = pendingAmount;
        int damageType = pendingType;
        pendingTarget = null;
        if (target != null && actor instanceof k0) {
            DuelBattleAuthority.damaged(target, amount, damageType, (k0) actor);
        }
        battle.I(actor);
    }

    /**
     * Blast and continuous-weapon damage, which no call site can name.
     *
     * <p>An exploding shell's blast damages whatever it overlaps and the two
     * continuous weapons damage their target every frame they hold it. Neither
     * knows which announced shot it came from, so neither authors an
     * {@code IMPACT}: the defender's periodic {@code FORT_SYNC} is what carries
     * their effect to the peer, which is exactly the drift correction that
     * event exists for. It is still reported here so a device can see how much
     * of its damage is arriving unattributed.</p>
     */
    public static void blast(f2 target, float amount, int damageType) {
        target.t(amount, damageType);
        DuelBattleAuthority.unattributed(target, amount);
    }

    /** See {@link #blast}. */
    public static void beam(f2 target, float amount, int damageType) {
        target.t(amount, damageType);
        DuelBattleAuthority.unattributed(target, amount);
    }

    /**
     * The hit this ordnance just resolved, between the two adjacent call sites
     * above. The game is single-threaded and both calls are statements of one
     * method, so this never spans a frame.
     */
    private static f2 pendingTarget;
    private static float pendingAmount;
    private static int pendingType;
    private static int resolvedShots;

    /** Shots that reached the ordnance hit handler, for check diagnostics. */
    public static int resolvedShots() {
        return resolvedShots;
    }

    private DuelNativeBattleHook() {
    }
}
