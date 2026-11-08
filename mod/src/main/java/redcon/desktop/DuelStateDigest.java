package redcon.desktop;

import o3.s0;
import o3.v1;
import o3.g2;
import o3.i0;
import o3.k0;
import o3.m0;
import o3.n0;
import o3.o0;
import o3.p0;
import redcon.duel.DuelMatch;
import redcon.duel.Side;

/** Stable Phase 06 digest of forts, crew, and simulation-bearing ordnance. */
public final class DuelStateDigest {
    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    public static long fortRoster(DuelMatch match) {
        if (match == null) throw new IllegalArgumentException("match is null");
        long hash = side(OFFSET, match.near(), Side.NEAR);
        hash = side(hash, match.far(), Side.FAR);
        hash = ordnance(hash, match);
        return shields(hash, match);
    }

    private static long side(long hash, Side side, int sideIndex) {
        hash = word(hash, sideIndex);
        hash = rooms(hash, side);
        hash = roomCombat(hash, side);
        hash = fortState(hash, side);
        hash = turrets(hash, side);
        return crew(hash, side);
    }

    private static long rooms(long hash, Side side) {
        s0 fort = side.fortress;
        hash = word(hash, fort.Q);
        for (int roomIndex = 0; roomIndex < fort.Q; roomIndex++) {
            v1 room = fort.R[roomIndex];
            hash = word(hash, roomIndex);
            hash = word(hash, room == null ? 0 : 1);
            if (room != null) {
                hash = word(hash, Float.floatToRawIntBits(room.X0));
                hash = word(hash, room.M0);
            }
        }
        return hash;
    }

    /**
     * Everything a room carries that decides its future, beyond health.
     *
     * <p>Health and {@code M0} are the end of a long chain. A room that is on
     * fire, holding neurotoxin, part-way through an EMP blackout, mid-burst,
     * reloading against a different mode's ammunition pool, or aimed somewhere
     * else has already diverged, and none of that reaches {@code X0} or
     * {@code M0} for seconds. That delay is why the captured traces kept
     * naming a room's health as the first difference: it was the first thing
     * being watched, not the first thing that moved.</p>
     *
     * <p>This is deliberately a separate component from {@link #rooms}, so the
     * older component keeps its meaning and a new trace can still be lined up
     * against the captures that already exist.</p>
     */
    private static long roomCombat(long hash, Side side) {
        s0 fort = side.fortress;
        hash = word(hash, fort.Q);
        for (int index = 0; index < fort.Q; index++) {
            v1 room = fort.R[index];
            hash = word(hash, index);
            if (room == null) {
                hash = word(hash, 0);
                continue;
            }
            hash = word(hash, 1);
            // Hazards: fire and gas, both of which keep damaging afterwards.
            hash = real(hash, room.a1);
            hash = real(hash, room.Y0);
            // EMP: charge banked this frame, its duration, when it landed and
            // how long the room stays dark. s0.k0 spends all of it.
            hash = word(hash, room.I1);
            hash = word(hash, room.J1);
            hash = word(hash, room.H1);
            hash = word(hash, room.L1);
            hash = real(hash, room.K1);
            // What the room will fire, and out of which pool. i2 is one
            // reload bank per firing mode; v1.F tops all of them up every
            // frame and v1.I moves the active one between them, so it is what
            // decides when each mode is next available.
            hash = word(hash, room.i1);
            hash = word(hash, modeIndex(room));
            hash = real(hash, room.b2);
            hash = real(hash, room.c2);
            if (room.i2 != null) {
                for (int pool = 0; pool < room.i2.length; pool++) {
                    hash = real(hash, room.i2[pool]);
                }
            }
            // Where it is in a burst, and the rolling accuracy register that
            // decides whether the next shell hits.
            hash = word(hash, room.Y1);
            hash = word(hash, room.Z1);
            hash = word(hash, room.a2);
            hash = word(hash, room.f2);
            hash = word(hash, room.g2);
            // Aim, damage bookkeeping and structural state.
            hash = word(hash, roomIndex(fort, room.h1));
            hash = word(hash, roomIndex(fort, room.d2));
            hash = word(hash, room.E1);
            hash = real(hash, room.j1);
            hash = word(hash, room.N1);
            hash = word(hash, room.M1 ? 1 : 0);
        }
        return hash;
    }

    /**
     * The fortress itself: power budget, ability charges and its airship.
     *
     * <p>An EMP does not damage anything. It takes power away, and
     * {@code s0.k0} then decides which rooms go dark. The budget, the drain
     * pool and the hold that follows it are the state that decision reads, and
     * none of it was hashed. Nor was the airship, which the ordnance walk
     * skips because it is not a shot.</p>
     */
    private static long fortState(long hash, Side side) {
        s0 fort = side.fortress;
        hash = real(hash, fort.h0);
        // Power: surplus, deficit, reserved, the EMP drain pool and its hold.
        hash = real(hash, fort.i0);
        hash = real(hash, fort.j0);
        hash = word(hash, fort.l0);
        hash = real(hash, fort.m0);
        hash = word(hash, fort.n0);
        // Which room categories are powered, undamaged, and present at all.
        hash = word(hash, fort.s0);
        hash = word(hash, fort.t0);
        hash = word(hash, fort.u0);
        // The three ability charges: scrambler, clandestine, fleet command.
        hash = real(hash, fort.E0);
        hash = real(hash, fort.H0);
        hash = real(hash, fort.B0);
        // Boarding and casualty counters, which decide the round's outcome.
        hash = word(hash, fort.Z0);
        hash = word(hash, fort.a1);
        hash = word(hash, fort.b1);
        hash = word(hash, fort.c1);
        return airship(hash, side);
    }

    /**
     * The airship, which the ordnance walk never sees.
     *
     * <p>{@code o3.a} is an {@code f2}, not a {@code p0}, so a transport
     * carrying soldiers to the other fortress, or a bomber carrying an EMP,
     * was outside the checksum for its whole flight. Its variant is
     * {@code X}, chosen from the bay room's payload index at launch.</p>
     */
    private static long airship(long hash, Side side) {
        o3.a ship = side.fortress.w0;
        hash = word(hash, ship == null ? 0 : 1);
        if (ship == null) {
            return hash;
        }
        hash = word(hash, ship.R);
        hash = word(hash, ship.X);
        hash = word(hash, ship.W);
        hash = word(hash, ship.Y ? 1 : 0);
        hash = word(hash, ship.j0 ? 1 : 0);
        hash = word(hash, ship.h0);
        hash = word(hash, ship.i0);
        hash = word(hash, ship.k0);
        hash = real(hash, ship.S);
        hash = real(hash, ship.T);
        hash = real(hash, ship.U);
        hash = real(hash, ship.V);
        hash = real(hash, ship.Z);
        hash = real(hash, ship.a0);
        hash = real(hash, ship.b0);
        hash = real(hash, ship.c0);
        hash = real(hash, ship.d0);
        hash = word(hash, ship.m0.a);
        hash = word(hash, roomIndex(side.fortress, ship.P));
        hash = word(hash, roomIndex(side.opponent().fortress, ship.f0));
        hash = word(hash, roomIndex(side.opponent().fortress, ship.g0));
        return hash;
    }

    /**
     * Active-defense turrets, which decide whether a rocket arrives.
     *
     * <p>{@code o3.d2} hangs off the fortress in {@code s0.P} rather than off
     * {@code s0.R}, so the room walk never reached it and it is not ordnance
     * either. A turret that is tracking a different missile, or part-way
     * through a different burst, shoots down a different rocket - and the
     * first thing the old digest would have seen is the room that rocket then
     * did or did not destroy.</p>
     *
     * <p>Its idle sweep is driven by the combat generator ({@code d2.G} draws
     * an angle and a delay), so a turret that has drifted is also evidence
     * that the shared stream has.</p>
     */
    private static long turrets(long hash, Side side) {
        s0 fort = side.fortress;
        hash = word(hash, fort.O);
        for (int index = 0; index < fort.O; index++) {
            o3.d2 turret = fort.P[index];
            hash = word(hash, index);
            if (turret == null) {
                hash = word(hash, 0);
                continue;
            }
            hash = word(hash, 1);
            hash = word(hash, turret.R);
            hash = real(hash, turret.d0);
            hash = real(hash, turret.g0);
            hash = real(hash, turret.e0);
            hash = real(hash, turret.f0);
            hash = word(hash, turret.b0);
            hash = word(hash, turret.k0);
            hash = word(hash, turret.q0);
            hash = word(hash, turret.r0);
            hash = word(hash, turret.s0);
            hash = word(hash, turret.t0);
            hash = word(hash, turret.c0 == null ? 0 : 1);
            hash = word(hash, turret.i0 == null ? 0 : 1);
        }
        return hash;
    }

    /**
     * Kinetic shields, which decide whether a shell reaches the fort at all.
     *
     * <p>{@code o3.c2} is another {@code f2} the ordnance walk skips. Its
     * stored energy is what an incoming shell is tested against, so two
     * devices that disagree about it disagree about which shells land - and
     * the trace would only have noticed once one of them did.</p>
     */
    private static long shields(long hash, DuelMatch match) {
        int count = 0;
        for (i0 entity = match.battle().H(); entity != null; entity = entity.p) {
            if (entity instanceof o3.c2) count++;
        }
        hash = word(hash, count);
        for (i0 entity = match.battle().H(); entity != null; entity = entity.p) {
            if (!(entity instanceof o3.c2)) continue;
            o3.c2 shield = (o3.c2) entity;
            hash = word(hash, shield.F == match.near().fortress ? 0 : 1);
            hash = word(hash, shield.E);
            hash = real(hash, shield.I);
            hash = word(hash, shield.J);
        }
        return hash;
    }

    /** Where this room's active weapon sits in its mode list, or negative. */
    private static int modeIndex(v1 room) {
        if (room.L0 == null || room.h2 == null) return -1;
        for (int i = 0; i < room.L0.length; i++) {
            if (room.L0[i] == room.h2) return i;
        }
        return -2;
    }

    private static long crew(long hash, Side side) {
        s0 fort = side.fortress;
        hash = word(hash, side.crewIdCount());
        hash = word(hash, side.crewCount());
        for (int index = 0; index < side.crewCount(); index++) {
            g2 crew = side.crew(index);
            hash = word(hash, index);
            hash = word(hash, crew == null ? 0 : 1);
            if (crew != null) {
                hash = word(hash, side.indexOfCrew(crew));
                hash = word(hash, crew.A0);
                hash = real(hash, crew.B0);
                hash = real(hash, crew.C0);
                hash = real(hash, crew.E0);
                hash = real(hash, crew.O0);
                hash = real(hash, crew.P0);
                hash = real(hash, crew.Q0);
                hash = word(hash, roomIndex(fort, crew.F0));
                hash = word(hash, roomIndex(fort, crew.J0));
                hash = word(hash, roomIndex(fort, crew.Y0));
                // G0 is the device-local selection highlight (a1.p0/Q).
                // H0 is boarding/fort ownership and remains combat state.
                hash = word(hash, crew.H0 ? 1 : 0);
                // b1 is when this soldier stops being incapacitated and c1 is
                // what they go back to being. A flashbang shell and a CHARON
                // impact both write them, and neither was hashed, so a stun
                // that landed on one device and not the other was invisible
                // until the soldier's position drifted.
                hash = word(hash, crew.b1);
                hash = word(hash, crew.c1);
                hash = word(hash, crew.N0);
            }
        }
        return hash;
    }

    private static int shieldCount(DuelMatch match) {
        int count = 0;
        for (i0 entity = match.battle().H(); entity != null; entity = entity.p) {
            if (entity instanceof o3.c2) count++;
        }
        return count;
    }

    private static int ordnanceCount(DuelMatch match) {
        int count = 0;
        for (i0 entity = match.battle().H(); entity != null;
             entity = entity.p) {
            if (entity instanceof p0 || entity instanceof n0
                    || entity instanceof m0) count++;
        }
        return count;
    }

    /**
     * The digest split into independent parts.
     *
     * <p>{@link #fortRoster} chains everything into one number, which proves
     * two devices differ but never says where. Each part here restarts from
     * the same offset, so the parts can be compared one against one across
     * two machines.</p>
     */
    public static String components(DuelMatch match) {
        Side near = match.near();
        Side far = match.far();
        return "nearRooms=" + shortHex(rooms(OFFSET, near))
                 + "(" + near.fortress.Q + ")"
             + " nearCrew=" + shortHex(crew(OFFSET, near))
                 + "(" + near.crewCount() + ")"
             + " farRooms=" + shortHex(rooms(OFFSET, far))
                 + "(" + far.fortress.Q + ")"
             + " farCrew=" + shortHex(crew(OFFSET, far))
                 + "(" + far.crewCount() + ")"
             + " ordnance=" + shortHex(ordnance(OFFSET, match))
                 + "(" + ordnanceCount(match) + ")"
             + " nearCombat=" + shortHex(roomCombat(OFFSET, near))
             + " farCombat=" + shortHex(roomCombat(OFFSET, far))
             + " nearFort=" + shortHex(fortState(OFFSET, near))
             + " farFort=" + shortHex(fortState(OFFSET, far))
             + " nearTurrets=" + shortHex(turrets(OFFSET, near))
                 + "(" + near.fortress.O + ")"
             + " farTurrets=" + shortHex(turrets(OFFSET, far))
                 + "(" + far.fortress.O + ")"
             + " shields=" + shortHex(shields(OFFSET, match))
                 + "(" + shieldCount(match) + ")";
    }

    /** Per-room and per-crew hashes, so a differing part names its member. */
    public static String detail(DuelMatch match) {
        StringBuilder out = new StringBuilder();
        for (int which = 0; which < 2; which++) {
            Side side = which == 0 ? match.near() : match.far();
            String name = which == 0 ? "near" : "far";
            s0 fort = side.fortress;
            out.append(name).append("Room");
            for (int i = 0; i < fort.Q; i++) {
                v1 room = fort.R[i];
                long h = OFFSET;
                h = word(h, room == null ? 0 : 1);
                if (room != null) {
                    h = word(h, Float.floatToRawIntBits(room.X0));
                    h = word(h, room.M0);
                }
                out.append(' ').append(i).append(':').append(shortHex(h));
            }
            // The same rooms again, hashed over the state that moves first.
            out.append('\n').append(name).append("Combat");
            for (int i = 0; i < fort.Q; i++) {
                v1 room = fort.R[i];
                long h = OFFSET;
                if (room != null) {
                    h = real(h, room.a1);
                    h = real(h, room.Y0);
                    h = word(h, room.I1);
                    h = word(h, room.J1);
                    h = word(h, room.H1);
                    h = word(h, room.L1);
                    h = real(h, room.K1);
                    h = word(h, room.i1);
                    h = word(h, modeIndex(room));
                    h = real(h, room.b2);
                    h = real(h, room.c2);
                    h = word(h, room.Y1);
                    h = word(h, room.Z1);
                    h = word(h, room.a2);
                    h = word(h, room.f2);
                    h = word(h, room.g2);
                    h = word(h, roomIndex(fort, room.h1));
                    h = word(h, roomIndex(fort, room.d2));
                    h = real(h, room.j1);
                }
                out.append(' ').append(i).append(':').append(shortHex(h));
            }
            out.append('\n').append(name).append("Crew");
            for (int i = 0; i < side.crewCount(); i++) {
                g2 c = side.crew(i);
                long h = OFFSET;
                h = word(h, c == null ? 0 : 1);
                if (c != null) {
                    h = word(h, c.A0);
                    h = real(h, c.B0);
                    h = real(h, c.C0);
                    h = real(h, c.E0);
                    h = real(h, c.O0);
                    h = real(h, c.P0);
                    h = real(h, c.Q0);
                    h = word(h, roomIndex(fort, c.F0));
                    h = word(h, roomIndex(fort, c.J0));
                    h = word(h, roomIndex(fort, c.Y0));
                    h = word(h, c.H0 ? 1 : 0);
                }
                out.append(' ').append(i).append(':').append(shortHex(h));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Exact room health bits and flags for the first divergent-frame trace. */
    public static String roomFields(DuelMatch match) {
        StringBuilder out = new StringBuilder();
        for (int which = 0; which < 2; which++) {
            Side side = which == 0 ? match.near() : match.far();
            String name = which == 0 ? "near" : "far";
            for (int index = 0; index < side.fortress.Q; index++) {
                v1 room = side.room(index);
                if (out.length() != 0) out.append(' ');
                out.append(name).append("Room").append(index).append('=');
                if (room == null) out.append("null");
                else out.append(Integer.toHexString(Float.floatToRawIntBits(room.X0)))
                    .append(':').append(Integer.toHexString(room.M0));
            }
        }
        return out.toString();
    }

    /**
     * Every crew field the digest covers, raw.
     *
     * <p>Hashes prove two devices differ; they never say by how much. Floats
     * are printed as decimal and as raw bits, because a one-ulp difference is
     * invisible in decimal and is exactly the kind of difference two runtimes
     * are permitted to produce.</p>
     */
    public static String crewFields(DuelMatch match) {
        StringBuilder out = new StringBuilder();
        for (int which = 0; which < 2; which++) {
            Side side = which == 0 ? match.near() : match.far();
            String name = which == 0 ? "near" : "far";
            s0 fort = side.fortress;
            for (int i = 0; i < side.crewCount(); i++) {
                g2 c = side.crew(i);
                out.append(name).append(' ').append(i);
                if (c == null) {
                    out.append(" null\n");
                    continue;
                }
                out.append(" A0=").append(c.A0);
                bits(out, " B0=", c.B0);
                bits(out, " C0=", c.C0);
                bits(out, " E0=", c.E0);
                bits(out, " O0=", c.O0);
                bits(out, " P0=", c.P0);
                bits(out, " Q0=", c.Q0);
                out.append(" F0=").append(roomIndex(fort, c.F0));
                out.append(" J0=").append(roomIndex(fort, c.J0));
                out.append(" Y0=").append(roomIndex(fort, c.Y0));
                out.append(" G0=").append(c.G0 ? 1 : 0);
                out.append(" H0=").append(c.H0 ? 1 : 0);
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void bits(StringBuilder out, String label, float value) {
        out.append(label).append(value).append('/')
           .append(Integer.toHexString(Float.floatToRawIntBits(value)));
    }

    private static String shortHex(long hash) {
        return Integer.toHexString((int) (hash ^ (hash >>> 32)));
    }

    private static long ordnance(long hash, DuelMatch match) {
        int count = 0;
        for (i0 entity = match.battle().H(); entity != null;
             entity = entity.p) {
            if (entity instanceof p0 || entity instanceof n0
                    || entity instanceof m0) count++;
        }
        hash = word(hash, count);
        for (i0 entity = match.battle().H(); entity != null;
             entity = entity.p) {
            if (!(entity instanceof p0) && !(entity instanceof n0)
                    && !(entity instanceof m0)) continue;
            int type = entity instanceof o0 ? 2
                : entity instanceof p0 ? 1 : entity instanceof n0 ? 3 : 4;
            hash = word(hash, type);
            k0 moving = (k0) entity;
            hash = real(hash, moving.A);
            hash = real(hash, moving.B);
            hash = real(hash, moving.C);
            hash = real(hash, moving.D);
            hash = real(hash, moving.E);
            hash = real(hash, moving.F);
            hash = real(hash, moving.G);
            hash = real(hash, moving.H);
            hash = real(hash, moving.J);
            hash = word(hash, moving.w);
            hash = word(hash, moving.z);
            if (entity instanceof p0) {
                p0 shot = (p0) entity;
                hash = real(hash, shot.P);
                hash = real(hash, shot.X);
                hash = real(hash, shot.Y);
                hash = real(hash, shot.Z);
                hash = real(hash, shot.a0);
                hash = word(hash, shot.N ? 1 : 0);
            }
            if (entity instanceof o0) {
                o0 shell = (o0) entity;
                hash = word(hash, shell.l0);
                hash = word(hash, shell.m0);
                hash = real(hash, shell.n0);
                hash = real(hash, shell.o0);
                hash = real(hash, shell.p0);
            }
        }
        return hash;
    }

    /**
     * Every ordnance field the digest covers, raw.
     *
     * <p>The counterpart of {@link #crewFields}, for the part of the state
     * that only exists while something is in flight. Rooms and crew are the
     * same on two devices for the first seconds of a round whatever the seed
     * was; a shell's position is the first thing that is not, which makes it
     * the first place a difference in the two runtimes' arithmetic can show.
     * Raw float bits, for the same reason they are printed for crew.</p>
     */
    public static String ordnanceFields(DuelMatch match) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (i0 entity = match.battle().H(); entity != null;
             entity = entity.p) {
            if (!(entity instanceof p0) && !(entity instanceof n0)
                    && !(entity instanceof m0)) continue;
            int type = entity instanceof o0 ? 2
                : entity instanceof p0 ? 1 : entity instanceof n0 ? 3 : 4;
            k0 moving = (k0) entity;
            out.append("shot ").append(index++).append(" type=").append(type);
            bits(out, " A=", moving.A);
            bits(out, " B=", moving.B);
            bits(out, " C=", moving.C);
            bits(out, " D=", moving.D);
            bits(out, " E=", moving.E);
            bits(out, " F=", moving.F);
            bits(out, " G=", moving.G);
            bits(out, " H=", moving.H);
            bits(out, " J=", moving.J);
            out.append(" w=").append(moving.w).append(" z=").append(moving.z);
            if (entity instanceof p0) {
                p0 shot = (p0) entity;
                bits(out, " P=", shot.P);
                bits(out, " X=", shot.X);
                bits(out, " Y=", shot.Y);
                bits(out, " Z=", shot.Z);
                bits(out, " a0=", shot.a0);
                out.append(" N=").append(shot.N ? 1 : 0);
            }
            if (entity instanceof o0) {
                o0 shell = (o0) entity;
                out.append(" l0=").append(shell.l0)
                   .append(" m0=").append(shell.m0);
                bits(out, " n0=", shell.n0);
                bits(out, " o0=", shell.o0);
                bits(out, " p0=", shell.p0);
            }
            out.append("\n");
        }
        return out.toString();
    }

    /**
     * A census of the battle's whole entity chain, by class.
     *
     * <p>The digest deliberately covers only the entities that bear
     * simulation - shots, shells, bombs. The chain also carries presentation:
     * flashes, smoke, casings, markers. Those are not state and must not be
     * hashed, but they are not harmless either, because REDCON draws them from
     * the same generator the simulation uses. Two devices that put a different
     * number of them on the chain have advanced that generator differently,
     * and this is what says which class did it.</p>
     */
    public static String entityCensus(DuelMatch match) {
        java.util.LinkedHashMap<String, Integer> byClass =
            new java.util.LinkedHashMap<>();
        int total = 0;
        // The chain Duel.entities counts, not the one the digest walks: the
        // two are linked differently, and it is this one whose length differs
        // between a device that renders effects and one that renders fewer.
        o3.a1 battle = match.battle();
        for (i0 entity = battle.j.r;
             entity != null && entity != battle.i && total < 100000;
             entity = entity.r) {
            byClass.merge(entity.getClass().getName(), 1, Integer::sum);
            total++;
        }
        StringBuilder out = new StringBuilder("census total=");
        out.append(total);
        for (java.util.Map.Entry<String, Integer> entry : byClass.entrySet()) {
            out.append(' ').append(entry.getKey())
               .append('=').append(entry.getValue());
        }
        return out.append("\n").toString();
    }

    private static int roomIndex(s0 fort, v1 room) {
        if (room == null) return -1;
        for (int i = 0; i < fort.Q; i++) if (fort.R[i] == room) return i;
        return -2;
    }

    private static long real(long hash, float value) {
        return word(hash, Float.floatToRawIntBits(value));
    }

    private static long word(long hash, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= PRIME;
        }
        return hash;
    }

    private DuelStateDigest() { }
}
