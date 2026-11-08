package redcon.duel;

import o3.g2;
import o3.v1;

/**
 * One thing a commander can tell their fortress to do.
 *
 * <h2>Why this exists rather than direct calls</h2>
 *
 * The engine already has the methods - {@code v1.s0(target)} aims a weapon,
 * {@code g2.Q(room)} sends a crew member somewhere. What it does not have is a
 * <em>name</em> for the act of ordering, because in the shipped game the act of
 * ordering is a mouse handler: {@code a1.n()} reads the cursor, decides what was
 * clicked, and calls the method, all in one place, and it does it for
 * {@code a1.U0} because that is who the player is.
 *
 * <p>Splitting the decision from the call is the whole of Phase 03. Once an
 * order is a value, it can come from a mouse, from a script, from the far side
 * of a hotseat keyboard, or - in Phase 04 - off a socket, and none of those
 * sources needs to know anything about the others.
 *
 * <h2>Everything is an index</h2>
 *
 * Rooms use their position in {@code s0.R}; crew use stable per-seat IDs from
 * {@link Side#crewById}. The engine's crew list can change after issuing an
 * order, so a current list position must never be sent as an identity. The
 * wire carries the order kind and two integer arguments, never object references.
 *
 * <p>An order is resolved against a {@link Side}, and that is what makes it
 * side-agnostic: the same {@code AIM 0 -> 3} means "my room 0 at their room 3"
 * whichever fortress the side happens to be.
 */
public final class Order {

    public enum Kind {
        /** Select one of my own rooms. {@code a} is its index. */
        SELECT_ROOM,
        /** Select one of the opponent's rooms, as a target. {@code a} is its index. */
        SELECT_ENEMY_ROOM,
        /** Drop the room and crew selection. */
        CLEAR_SELECTION,
        /** Aim my weapon room {@code a} at the opponent's room {@code b}. */
        AIM,
        /** Add my crew member {@code a} to the selection. */
        SELECT_CREW,
        /** Send my crew member {@code a} to my room {@code b}. */
        MOVE_CREW,
        /** Send every selected crew member to my room {@code a}. */
        MOVE_SELECTED_CREW,
        /** Set the power switch on my room {@code a}; {@code b} is 0 or 1. */
        SET_ROOM_POWER,
        /** Set manual fire on my room {@code a}; {@code b} is 0 or 1. */
        SET_ROOM_FIRE_MODE,
        /** Set a one-shot manual-fire request; {@code b} is 0 or 1. */
        SET_MANUAL_FIRE,
        /**
         * Select the payload my room {@code a} loads next; {@code b} indexes
         * {@code v1.r0}. On a Fleet Command room this is the airship variant -
         * transport or EMP bomber - and it is read at launch, not at selection.
         */
        SET_AMMO,
        /**
         * Select the firing mode of my weapon room {@code a}; {@code b} indexes
         * {@code v1.L0}. This is REDCON's "ammo type": incendiary, neurotoxin,
         * flashbang, shredder. It also moves the room's reload bank.
         */
        SET_WEAPON_MODE,
        /**
         * Launch my airship, or recall it if one is already out. Which of the
         * two happens is decided when the order is applied, not when it is
         * issued, because the airship may have died in between.
         */
        AIRSHIP_ACTION,
        /** Load or unload my airship's troops; {@code b} is 0 or 1. */
        SET_AIRSHIP_CARGO,
        /**
         * Send my airship to room {@code a} of my fortress ({@code b} 0) or of
         * the opponent's ({@code b} 1).
         */
        SET_AIRSHIP_TARGET,
        /**
         * Fire my room {@code a} now, ignoring its reload gate - the scrambler
         * pulse and the manual ICBM launch. A scrambler also spends the
         * fortress's charge, exactly as the HUD button does.
         */
        FORCE_FIRE,
        /** Run my clandestine conversion against an enemy soldier. */
        CLANDESTINE_CONVERT,
        /**
         * Put my soldier {@code a} aboard an airship - mine ({@code b} 0) or
         * the opponent's ({@code b} 1).
         *
         * <p>One order per soldier rather than one for the selection: the two
         * seats are allowed to have different soldiers selected, so "board
         * whoever I have highlighted" is not a thing the peer can carry out.</p>
         */
        BOARD_AIRSHIP,
        /** Move my soldier inside the opponent's fortress after boarding. */
        MOVE_CREW_TO_ENEMY_ROOM
    }

    public final Kind kind;
    public final int a;
    public final int b;

    private Order(Kind kind, int a, int b) {
        this.kind = kind;
        this.a = a;
        this.b = b;
    }

    /** A value copy for the event boundary and, later, the packet codec. */
    public Order copy() {
        return new Order(kind, a, b);
    }

    public static Order selectRoom(int roomIndex) {
        return new Order(Kind.SELECT_ROOM, roomIndex, 0);
    }

    public static Order selectEnemyRoom(int roomIndex) {
        return new Order(Kind.SELECT_ENEMY_ROOM, roomIndex, 0);
    }

    public static Order clearSelection() {
        return new Order(Kind.CLEAR_SELECTION, 0, 0);
    }

    public static Order aim(int weaponRoomIndex, int enemyRoomIndex) {
        return new Order(Kind.AIM, weaponRoomIndex, enemyRoomIndex);
    }

    public static Order selectCrew(int crewIndex) {
        return new Order(Kind.SELECT_CREW, crewIndex, 0);
    }

    public static Order moveCrew(int crewIndex, int roomIndex) {
        return new Order(Kind.MOVE_CREW, crewIndex, roomIndex);
    }

    public static Order moveCrewToEnemyRoom(int crewId, int roomIndex) {
        return new Order(Kind.MOVE_CREW_TO_ENEMY_ROOM, crewId, roomIndex);
    }

    public static Order moveSelectedCrew(int roomIndex) {
        return new Order(Kind.MOVE_SELECTED_CREW, roomIndex, 0);
    }

    public static Order setRoomPower(int roomIndex, boolean enabled) {
        return new Order(Kind.SET_ROOM_POWER, roomIndex, enabled ? 1 : 0);
    }

    public static Order setRoomFireMode(int roomIndex, boolean enabled) {
        return new Order(Kind.SET_ROOM_FIRE_MODE, roomIndex, enabled ? 1 : 0);
    }

    public static Order setManualFire(int roomIndex, boolean requested) {
        return new Order(Kind.SET_MANUAL_FIRE, roomIndex, requested ? 1 : 0);
    }

    public static Order setAmmo(int roomIndex, int ammoIndex) {
        return new Order(Kind.SET_AMMO, roomIndex, ammoIndex);
    }

    public static Order setWeaponMode(int roomIndex, int modeIndex) {
        return new Order(Kind.SET_WEAPON_MODE, roomIndex, modeIndex);
    }

    public static Order airshipAction() {
        return new Order(Kind.AIRSHIP_ACTION, 0, 0);
    }

    public static Order setAirshipCargo(boolean loaded) {
        return new Order(Kind.SET_AIRSHIP_CARGO, 0, loaded ? 1 : 0);
    }

    public static Order setAirshipTarget(int roomIndex, boolean opponentFort) {
        return new Order(Kind.SET_AIRSHIP_TARGET, roomIndex,
                         opponentFort ? 1 : 0);
    }

    public static Order forceFire(int roomIndex) {
        return new Order(Kind.FORCE_FIRE, roomIndex, 0);
    }

    public static Order clandestineConvert() {
        return new Order(Kind.CLANDESTINE_CONVERT, 0, 0);
    }

    public static Order boardAirship(int crewIndex, boolean opponentAirship) {
        return new Order(Kind.BOARD_AIRSHIP, crewIndex,
                         opponentAirship ? 1 : 0);
    }

    /**
     * Carry this order out on behalf of {@code side}.
     *
     * @return true if the engine accepted it. A refusal is normal and not an
     *         error - a weapon out of arc, a room with no route for the crew, an
     *         index that no longer names a live room. The caller reports the
     *         count rather than treating it as a failure.
     */
    public boolean apply(Side side) {
        switch (kind) {
            case SELECT_ROOM: {
                v1 room = side.room(a);
                if (room == null) {
                    return false;
                }
                side.select(room);
                return true;
            }
            case SELECT_ENEMY_ROOM: {
                v1 room = side.opponent().room(a);
                if (room == null) {
                    return false;
                }
                side.select(room);
                return true;
            }
            case CLEAR_SELECTION: {
                side.select(null);
                side.clearCrewSelection();
                return true;
            }
            case AIM: {
                v1 weapon = side.room(a);
                v1 target = side.opponent().room(b);
                if (weapon == null || target == null || !weapon.G()) {
                    return false;
                }
                // The same call a1.n() makes on the player's selected room when
                // the cursor is over an enemy room. Nothing in it is specific to
                // U0 - it is a method on the room doing the shooting.
                weapon.s0(target);
                return true;
            }
            case SELECT_CREW: {
                g2 crew = side.crewById(a);
                if (crew == null) {
                    return false;
                }
                side.selectCrew(crew);
                return true;
            }
            case MOVE_CREW:
            case MOVE_CREW_TO_ENEMY_ROOM: {
                g2 crew = side.crewById(a);
                v1 room = (kind == Kind.MOVE_CREW ? side : side.opponent()).room(b);
                if (crew == null || room == null) {
                    return false;
                }
                return moveOne(crew, room);
            }
            case MOVE_SELECTED_CREW: {
                v1 room = side.room(a);
                if (room == null || side.selectedCrew().isEmpty()) {
                    return false;
                }
                boolean any = false;
                for (g2 crew : side.selectedCrew()) {
                    any |= moveOne(crew, room);
                }
                return any;
            }
            case SET_ROOM_POWER: {
                v1 room = side.room(a);
                if (room == null || (b != 0 && b != 1)) return false;
                room.q0(b == 1);
                return true;
            }
            case SET_ROOM_FIRE_MODE: {
                v1 room = side.room(a);
                if (room == null || (b != 0 && b != 1)) return false;
                room.r0(b == 1);
                return true;
            }
            case SET_MANUAL_FIRE: {
                v1 room = side.room(a);
                if (room == null || (b != 0 && b != 1)) return false;
                room.M0 = (room.M0 & ~16384) | (b == 1 ? 16384 : 0);
                return true;
            }
            case SET_AMMO: {
                v1 room = side.room(a);
                if (room == null || room.r0 == null
                        || b < 0 || b >= room.r0.length) {
                    return false;
                }
                // a1.c0 event 12 writes this index and nothing else. What it
                // selects is read later, by s0.E, when the airship launches.
                room.i1 = b;
                return true;
            }
            case SET_WEAPON_MODE: {
                v1 room = side.room(a);
                if (room == null || room.L0 == null
                        || b < 0 || b >= room.L0.length) {
                    return false;
                }
                // Not a label change: v1.I also moves the reload bank between
                // the two modes' pools, so it decides when the room next fires.
                room.I(room.L0[b]);
                return true;
            }
            case AIRSHIP_ACTION: {
                // The engine's own branch, re-evaluated here rather than at the
                // click. Between issuing and committing, the airship can have
                // been shot down, and then this has to become a launch.
                o3.a airship = side.fortress.w0;
                if (airship == null) {
                    return side.fortress.E() != null;
                }
                airship.A();
                airship.C(false);
                return true;
            }
            case SET_AIRSHIP_CARGO: {
                o3.a airship = side.fortress.w0;
                if (airship == null || (b != 0 && b != 1)) return false;
                airship.C(b == 1);
                return true;
            }
            case SET_AIRSHIP_TARGET: {
                o3.a airship = side.fortress.w0;
                if (airship == null || (b != 0 && b != 1)) return false;
                v1 room = (b == 1 ? side.opponent() : side).room(a);
                if (room == null) return false;
                airship.B(room);
                return true;
            }
            case FORCE_FIRE: {
                v1 room = side.room(a);
                if (room == null) return false;
                // a1.c0 event 7 zeroes the scrambler charge in the same breath
                // as it fires the relay. Spending it here keeps the charge on
                // the committed frame instead of the clicking device's frame.
                if (room == side.fortress.F0) {
                    side.fortress.E0 = 0.0f;
                }
                room.P();
                return true;
            }
            case BOARD_AIRSHIP: {
                if (b != 0 && b != 1) return false;
                g2 crew = side.crewById(a);
                o3.a airship =
                    (b == 1 ? side.opponent() : side).fortress.w0;
                if (crew == null || airship == null || !crew.A()) return false;
                // a1.U's own guard: a soldier can only board an airship that
                // is at the fortress they are standing in.
                v1 room = crew.F0;
                if (room == null || room.u0 != airship.f0.u0) return false;
                crew.O(airship);
                return true;
            }
            case CLANDESTINE_CONVERT: {
                // s0.A picks the victim and zeroes H0 itself; it needs no
                // argument because the choice is a function of both fortresses.
                side.fortress.A();
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Send one crew member to one room.
     *
     * {@code a1.g0} is the engine's version of this and it does the same three
     * things: skip anyone who is dead or otherwise unavailable ({@code A()}),
     * skip anyone who cannot get there ({@code B(room)}), and otherwise issue
     * {@code Q(room)}.
     */
    private static boolean moveOne(g2 crew, v1 room) {
        if ((!crew.A() && !crew.H()) || !crew.B(room)) {
            return false;
        }
        // Match a1.g0's same-room cancellation branch at the admitted frame.
        if (crew.F0 == room && !crew.G()) {
            crew.C();
            return true;
        }
        return crew.Q(room);
    }

    @Override
    public String toString() {
        switch (kind) {
            case AIM:
                return "AIM room " + a + " -> enemy room " + b;
            case MOVE_CREW:
                return "MOVE_CREW " + a + " -> room " + b;
            case MOVE_SELECTED_CREW:
                return "MOVE_SELECTED_CREW -> room " + a;
            case SET_ROOM_POWER:
                return "SET_ROOM_POWER room " + a + " enabled=" + (b == 1);
            case SET_ROOM_FIRE_MODE:
                return "SET_ROOM_FIRE_MODE room " + a + " enabled=" + (b == 1);
            case SET_MANUAL_FIRE:
                return "SET_MANUAL_FIRE room " + a + " requested=" + (b == 1);
            case SET_AMMO:
                return "SET_AMMO room " + a + " payload=" + b;
            case SET_WEAPON_MODE:
                return "SET_WEAPON_MODE room " + a + " mode=" + b;
            case AIRSHIP_ACTION:
                return "AIRSHIP_ACTION";
            case SET_AIRSHIP_CARGO:
                return "SET_AIRSHIP_CARGO loaded=" + (b == 1);
            case SET_AIRSHIP_TARGET:
                return "SET_AIRSHIP_TARGET room " + a
                       + (b == 1 ? " (enemy fort)" : " (own fort)");
            case FORCE_FIRE:
                return "FORCE_FIRE room " + a;
            case CLANDESTINE_CONVERT:
                return "CLANDESTINE_CONVERT";
            case BOARD_AIRSHIP:
                return "BOARD_AIRSHIP crew " + a
                       + (b == 1 ? " (their airship)" : " (my airship)");
            case CLEAR_SELECTION:
                return "CLEAR_SELECTION";
            default:
                return kind + " " + a;
        }
    }
}
