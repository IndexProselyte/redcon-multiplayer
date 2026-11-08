package redcon.desktop;

import c1.a;
import o3.a1;
import o3.c1;
import o3.h1;
import o3.i0;
import o3.s0;
import o3.z0;

/**
 * Arm and step a two-sided battle, headlessly and repeatably.
 *
 * This is {@code a1.A0} - the game's own battle setup - with the widget, audio
 * and scenery work left out, plus the resets that method gets for free and a
 * harness does not. Phase 03 builds on this, so it lives on its own rather than
 * inside whichever harness needed it first.
 *
 * <h2>Everything static has to be put back</h2>
 *
 * A battle is mostly static state: {@code a1.U0} and {@code a1.V0} are the two
 * fortresses, {@code a1.W0} and {@code a1.X0} the two AI commanders, and the
 * clock lives on {@code o3.c1}. The game only ever arms one battle at a time
 * from a fresh screen, so {@code A0} can assume a lot. A harness that runs duel
 * after duel in one process cannot, and two pieces of leftover state are enough
 * to make a battle sit still and do nothing:
 *
 * <ul>
 *   <li><b>Entity stamps.</b> {@code c1.D} stamps an entity with the clock as
 *       it stands, and {@code c1.J} only ticks entities whose stamp is behind
 *       it. Arm a fortress while the clock still reads the end of the last
 *       battle, then zero the clock, and that fortress sits out the whole
 *       run.</li>
 *   <li><b>The AI think schedule.</b> {@code n3.q.t} builds its subsystems only
 *       when they are null, so a re-bound commander keeps a next-think time
 *       measured against the previous battle's clock and stays silent for as
 *       long as that clock ran. Clearing the subsystems forces it to rebuild
 *       them from the object table.</li>
 * </ul>
 *
 * With both handled, the same duel gives the same result whether it is the
 * first battle in a process or the tenth - which is what Phase 04 needs, since
 * event-sync netcode is only as good as the simulation being reproducible.
 */
public final class Duel {

    private static int randomT;
    private static int randomU;
    private static boolean randomCaptured;

    /** mojo.b.u at 60fps: (60/2 + 7920) / 60 ticks per frame. */
    public static final int TICKS_PER_FRAME = (60 / 2 + 7920) / 60;

    /** One second, in the units the battle clock counts. */
    public static final int TICKS_PER_SECOND = 7920;

    /**
     * The two team ids, which are the only two the engine has.
     *
     * {@code v1.a0(team)} reads {@code (F1 >> (team * 16)) & 0xFFFF}, so a room
     * carries exactly two per-team words and the ids can only be 0 and 1. A
     * fortress takes its id from its loadout ({@code s0.Q} copies
     * {@code h1.b} into {@code s0.y}) and stamps it onto every room and crew
     * member it builds.
     *
     * <p>Leaving both loadouts at zero puts both fortresses on team 0, and the
     * effect is not subtle: the far fortress will not shoot, because its
     * weapons resolve the near fortress as friendly. That was the state before
     * these ids were assigned, and it is why {@code TEAM_NEAR} and
     * {@code TEAM_FAR} exist rather than a bare 0 and 1 at the call site.
     */
    public static final int TEAM_NEAR = 0;

    /** The far fortress's team. See {@link #TEAM_NEAR}. */
    public static final int TEAM_FAR = 1;

    private Duel() {
    }

    /**
     * Build both fortresses, put them in the entity list, and bind a commander
     * to each. The returned battle is ready to {@link #frame}.
     */
    public static a1 arm(z0 attacker, z0 defender) throws Exception {
        clearPools();
        // Put the generator back, so the tenth duel in a process is the first.
        resetRandom();
        // Clock first. D() stamps entities with it, so anything armed while it
        // still reads the end of the last battle never gets ticked.
        mojo.b.u = TICKS_PER_FRAME;
        a1.T0 = 1;
        a1.S0 = false;
        a1.R0 = 1;
        a1.Z0 = 0;
        c1.n = 0;
        c1.o = 0;
        c1.p = 0;
        c1.q = 0.0f;

        a1 battle = new a1();
        a1.Q0 = battle;

        // The entity list is a circular list between two sentinels and nothing
        // links them on a fresh battle, so E() cannot bootstrap itself.
        battle.i.q = battle.j;
        battle.j.r = battle.i;
        battle.E();

        reset(a1.U0);
        reset(a1.V0);

        h1 attackerLoadout = RecoveredGame.allocate(h1.class);
        attackerLoadout.f = attacker;
        attackerLoadout.b = TEAM_NEAR;
        h1 defenderLoadout = RecoveredGame.allocate(h1.class);
        defenderLoadout.f = defender;
        defenderLoadout.b = TEAM_FAR;

        a1.U0.Q(attackerLoadout, 1);
        a1.V0.Q(defenderLoadout, -1);
        a1.U0.S();
        a1.V0.S();
        battle.D(a1.U0);
        battle.D(a1.V0);

        // Force each commander to rebuild its subsystems from the object table,
        // so it cannot carry a think schedule out of the previous battle.
        a1.W0.v = null;
        a1.X0.v = null;
        a1.W0.t(a1.U0);
        a1.X0.t(a1.V0);

        // A mission record, so the battle has a backdrop and a camera. e1.a
        // fills the scenery fields off a mission config, and takes the random
        // branch for every field the config leaves at zero - which an empty one
        // does for all of them. B() is what turns that into a camera.
        q3.v config = RecoveredGame.allocate(q3.v.class);
        o3.e1 mission = RecoveredGame.allocate(o3.e1.class);
        mission.a = config;
        mission.b = attackerLoadout;
        mission.c = defenderLoadout;
        mission.a(config);
        battle.h0 = mission;
        battle.B();
        prepareFloatingLabel(battle);
        return battle;
    }

    /**
     * Drop every object free list, so a battle allocates fresh rather than
     * recycling what the last one left behind.
     *
     * mojo.f pools by prototype: {@code a()} pops the head of a free list
     * chained through field {@code b} and hands it straight back without
     * clearing it, so any field the caller does not overwrite carries over.
     * The game never notices because it arms one battle per screen; a harness
     * running duel after duel does, and the effect is not subtle - shells left
     * over from a previous battle change how much damage lands.
     *
     * Nulling a prototype's head empties its pool without touching anything
     * live: an in-use object points at its prototype through the same field,
     * not the other way round.
     */
    public static void clearPools() {
        if (mojo.f.c == null) {
            return;
        }
        for (mojo.f prototype : mojo.f.c) {
            if (prototype != null) {
                prototype.b = null;
            }
        }
    }

    /** Capture the engine generator once, then reset both of its streams. */
    public static void seedRandom() {
        if (!randomCaptured) {
            randomT = a.t;
            randomU = a.u;
            randomCaptured = true;
        }
        resetRandom();
    }

    /**
     * Seed the generator from a committed value, on every platform.
     *
     * <p>{@code c1.a.j} is a {@code long} that {@code workStart} stamps with
     * {@code System.nanoTime()} - a timestamp, not the generator. The
     * generator is the multiply-with-carry pair {@code c1.a.t}/{@code c1.a.u}
     * that {@code F()} advances, with {@code p}, {@code q}, {@code r} and
     * {@code s} a second stream derived from it.</p>
     *
     * <p>Phase 06 originally assigned the session seed to {@code j}, so the
     * committed seed never reached the generator and each platform kept its
     * own: the desktop bring-up skips {@code workStart}'s clock warm-up and is
     * deterministic, while Android runs the real warm-up, which advances the
     * generator {@code (int) System.currentTimeMillis() & 0xff} times and is
     * therefore different every launch. Crew state is drawn from this, so two
     * peers placed their crews differently and desynchronised at frame zero
     * while their rooms and ordnance matched exactly.</p>
     *
     * <p>Both halves are forced odd so the pair can never be seeded to a
     * degenerate zero state.</p>
     */
    public static void seedRandomFrom(long seed) {
        a.t = (int) (seed >>> 32) | 1;
        a.u = (int) seed | 1;
        int derived = a.F();
        a.p = derived;
        a.q = derived;
        a.r = 0;
        a.s = new int[64];
    }

    /** Restore the deterministic generator state used by each duel round. */
    public static void resetRandom() {
        if (randomCaptured) {
            a.t = randomT;
            a.u = randomU;
        }
        int seed = a.F();
        a.p = seed;
        a.q = seed;
        a.r = 0;
        a.s = new int[64];
    }

    /**
     * Build the floating "MISS" label a detonation spawns.
     *
     * When a shell lands on nothing it does not just disappear: {@code p0.D}
     * clones {@code a1.K0} into a drifting label and reads the font off it on
     * the way. That label is normally built by {@code q3.b.K} along with the
     * rest of the battle screen, so a battle armed directly has never had one -
     * and the first shot that misses takes the whole frame down with it, part
     * way through the entity loop.
     *
     * It only shows up when a shot happens to miss, which is why it survived
     * so long: change the random seed and it appears or does not. That is the
     * worst kind of intermittent, so the label is built here rather than
     * tolerated in the tick.
     *
     * Copied from the screen builder, font and all. Needs the fonts, so it runs
     * after the boot pump; without them it does nothing and a miss still throws.
     */
    private static void prepareFloatingLabel(a1 battle) {
        c3.c font = h3.c.m;
        if (font == null) {
            return;
        }
        q3.z floater = battle.K0;
        floater.t = 30;
        q3.a0 label = floater.v;
        if (label.r == null) {
            label.r = font.h();
        }
        label.j = (byte) 1;
        label.i = (byte) 64;
        label.r.o(48, mojo.Base.NLS("MISS"));
        label.h = -16744193;
    }

    /** The per-battle fields a1.A0 clears before it builds anything. */
    private static void reset(s0 fortress) {
        fortress.N0 = 0.0f;
        fortress.O0 = 0.0f;
        fortress.M0 = -1;
    }

    /**
     * One update-only frame: the entity simulation, both commanders, then the
     * clock. This is a1.C() without its presentation tail - the commanders are
     * the R0 == 1 branch of C(), called directly for the same reason J() is.
     */
    public static void frame(a1 battle) {
        battle.J();
        a1.W0.p();
        a1.X0.p();
        battle.p();
    }

    /**
     * Make sure a frame is worth some time.
     *
     * {@code mojo.b.u} is how many ticks {@code a1.p()} adds per frame, and the
     * shipped game fills it in {@code GameToolkit.initFramePacing} as
     * {@code (fps/2 + 7920) / fps}. Nothing in the boot pump does that, so a
     * battle armed any way other than through {@link #arm} - through the game's
     * own battle screen, for instance - comes up with it at zero.
     *
     * <p>The failure that causes is worth naming, because it looks like
     * anything but a clock: the battle renders correctly, entities are in the
     * list, orders are accepted, and nothing at all happens. {@code c1.o} never
     * advances, so {@code c1.J} never finds an entity whose stamp is behind the
     * clock, so nothing is ever ticked.
     */
    /**
     * Pin one simulation frame to one sixtieth of a second of world time.
     *
     * <p>This used to assign only when {@code mojo.b.u} was zero, which meant
     * it never fired on Android: REDCON's own frame pacing had already set the
     * value from the device's refresh target, so a 30&nbsp;Hz handset ran
     * {@code 7920 / 30 = 264} ticks per frame while the desktop ran
     * {@code 7920 / 60 = 132}. Lockstep then admitted frames of two different
     * durations on the two peers - identical inputs, identical seed, identical
     * arithmetic, and crew that had walked twice as far on one device by frame
     * zero.</p>
     *
     * <p>It is asserted rather than defaulted, and re-asserted every product
     * frame, because the engine's pacing code can rewrite the value at any
     * time. The previous value is returned so the caller can put the base game
     * back the way it found it.</p>
     */
    public static int ensureFramePacing() {
        int previous = mojo.b.u;
        mojo.b.u = TICKS_PER_FRAME;
        return previous;
    }

    /**
     * One update-only frame with neither commander running.
     *
     * The difference between this and {@link #frame} is the whole of the
     * player/AI split. {@code a1.C} ticks only {@code X0} - the commander bound
     * to {@code V0} - and never {@code W0}, because in the shipped game the
     * fortress {@code W0} is bound to is the one the player drives through the
     * UI. So "who is a human" is expressed purely as "whose commander does not
     * get called", and a side with no commander is a side waiting for orders.
     */
    public static void frameWithoutCommanders(a1 battle) {
        battle.J();
        battle.p();
    }

    /** Total remaining structure across a fortress's rooms. */
    public static float structure(s0 fortress) {
        float total = 0.0f;
        for (int i = 0; i < fortress.Q; i++) {
            total += fortress.R[i].X0;
        }
        return total;
    }

    /** Rooms carrying at least one weapon record. */
    public static int weaponRooms(s0 fortress) {
        int n = 0;
        for (int i = 0; i < fortress.Q; i++) {
            o3.v1 room = fortress.R[i];
            if (room != null && room.L0 != null && room.L0.length > 0) {
                n++;
            }
        }
        return n;
    }

    /** How many entities are currently live in the battle. */
    public static int entities(a1 battle) {
        int n = 0;
        for (i0 e = battle.j.r; e != null && e != battle.i && n < 100000; e = e.r) {
            n++;
        }
        return n;
    }

    /** Seconds of battle time elapsed. */
    public static float seconds() {
        return c1.o / (float) TICKS_PER_SECOND;
    }
}
