package redcon.duel;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import o3.a1;
import o3.g2;
import o3.s0;
import o3.v1;

/**
 * One commander's half of a duel: their fortress, and the state that in the
 * shipped game is global because there was only ever one player.
 *
 * <h2>What is actually global, and why it matters</h2>
 *
 * REDCON is a single-player game, so the engine keeps exactly one selection:
 * {@code a1.E0} is <em>the</em> selected room and {@code a1.F0} is <em>the</em>
 * selected crew list. Both are fields on the battle, not on a player, because
 * the battle only ever had one player in it. A duel needs two of each, and this
 * class is where the second one lives.
 *
 * <p>The engine's copies are not abandoned - the HUD reads them, so whichever
 * side currently holds the seat has its selection mirrored back into
 * {@code a1.E0} by {@link #takeSeat}. That keeps the game's own screen correct
 * without the game knowing there are two players.
 *
 * <h2>Sides are addressed by index, not by object</h2>
 *
 * Rooms use their placed index. Crew commands use monotonically assigned
 * per-seat IDs: the engine's lists swap-remove on death and boarding, and d0
 * excludes soldiers abroad. Reusing a list index could command a different
 * soldier when a delayed order arrives after a casualty.
 */
public final class Side {

    /** The near fortress: {@code a1.U0}, team 0, facing +1, drawn on the left. */
    public static final int NEAR = 0;

    /** The far fortress: {@code a1.V0}, team 1, facing -1, drawn on the right. */
    public static final int FAR = 1;

    /** {@link #NEAR} or {@link #FAR}. Also the engine team id. */
    public final int index;

    /** Whatever the player is called. Phase 05 gets this from the lobby. */
    public final String name;

    /** This side's fortress. {@code a1.U0} for the near side, {@code a1.V0} for the far. */
    public final s0 fortress;

    /** Set once both sides exist. */
    private Side opponent;

    /** This side's selected room - the engine's {@code a1.E0}, one per player. */
    private v1 selectedRoom;

    /** This side's selected crew - the engine's {@code a1.F0}, one per player. */
    private final List<g2> selectedCrew = new ArrayList<>();
    private final List<g2> crewIds = new ArrayList<>();
    private final IdentityHashMap<g2, Integer> idsByCrew = new IdentityHashMap<>();

    /** Structure this side has taken off the opponent. The match-clock tiebreak. */
    private float damageDealt;

    /** The opponent's structure when the match started, to measure against. */
    private float opponentStructureAtStart;

    Side(int index, String name, s0 fortress) {
        this.index = index;
        this.name = name;
        this.fortress = fortress;
        refreshCrewIds();
    }

    void pairWith(Side other) {
        this.opponent = other;
        this.opponentStructureAtStart = structureOf(other.fortress);
    }

    /** The other commander's side. */
    public Side opponent() {
        return opponent;
    }

    /** True for the fortress the engine draws on the left. */
    public boolean isNear() {
        return index == NEAR;
    }

    public v1 selectedRoom() {
        return selectedRoom;
    }

    /** Selected crew, in selection order. Never null; may be empty. */
    public List<g2> selectedCrew() {
        return selectedCrew;
    }

    /**
     * Select a room, or pass null to clear.
     *
     * The room is only accepted if it belongs to this side or to the opponent -
     * a selection is either "my room, to give it an order" or "their room, as a
     * target". Anything else is a picking bug and is dropped rather than stored.
     */
    public void select(v1 room) {
        if (room != null && room.u0 != fortress && room.u0 != opponent.fortress) {
            return;
        }
        this.selectedRoom = room;
        mirrorSelection();
    }

    /** Add a crew member to this side's selection, if it is this side's crew. */
    public void selectCrew(g2 crew) {
        if (crew != null && !selectedCrew.contains(crew) && crew.y0 == fortress) {
            selectedCrew.add(crew);
            mirrorCrewSelection();
        }
    }

    public void clearCrewSelection() {
        selectedCrew.clear();
        mirrorCrewSelection();
    }

    /**
     * Make this side the one the engine's HUD is describing.
     *
     * Only one side can hold the seat at a time, because there is only one
     * {@code a1.E0}. In hotseat that follows whoever is at the keyboard; under
     * Phase 04 it will simply be the local player, permanently.
     */
    public void takeSeat() {
        mirrorSelection();
        mirrorCrewSelection();
    }

    /**
     * Read the engine's live selection back into this side.
     *
     * <p>The battle screen drives {@code a1.E0}/{@code a1.F0} directly when a
     * commander plays through REDCON's own input pipeline, so a side that is
     * about to hand over the controls has to take its selection back out of
     * the engine before the incoming side overwrites it.</p>
     */
    public void captureSeat() {
        a1 battle = a1.Q0;
        if (battle == null) {
            return;
        }
        selectedRoom = battle.E0;
        selectedCrew.clear();
        com.android.billingclient.api.w selection = battle.F0;
        Object[] items = (Object[]) selection.b;
        if (items == null) {
            return;
        }
        for (int i = 0; i < selection.a; i++) {
            g2 crew = (g2) items[i];
            if (crew != null && crew.y0 == fortress) {
                selectedCrew.add(crew);
            }
        }
    }

    private void mirrorSelection() {
        a1 battle = a1.Q0;
        if (battle != null) {
            battle.E0 = selectedRoom;
        }
    }

    /** Keep the shipped soldier highlights and HUD list in step with this seat. */
    private void mirrorCrewSelection() {
        a1 battle = a1.Q0;
        if (battle == null) {
            return;
        }
        battle.Q();
        for (g2 crew : selectedCrew) {
            if (crew != null && crew.A()) {
                crew.G0 = true;
                crew.j0();
                battle.F0.a(crew);
            }
        }
    }

    /** The room at this index, or null if the index is not a placed room. */
    public v1 room(int index) {
        if (index < 0 || index >= fortress.Q) {
            return null;
        }
        return fortress.R[index];
    }

    /** The index {@link #room} would take to get this room back, or -1. */
    public int indexOf(v1 room) {
        for (int i = 0; i < fortress.Q; i++) {
            if (fortress.R[i] == room) {
                return i;
            }
        }
        return -1;
    }

    /** This side's current roster, including soldiers abroad and aboard ships. */
    public g2 crew(int index) {
        Object[] items = (Object[]) fortress.X.b;
        if (items == null || index < 0 || index >= fortress.X.a) {
            return null;
        }
        return (g2) items[index];
    }

    /** Stable command ID, or -1 if this seat no longer owns the soldier. */
    public int indexOfCrew(g2 crew) {
        Integer id = idsByCrew.get(crew);
        return id != null && crew.y0 == fortress ? id : -1;
    }

    /** Resolve a delayed command. Retired IDs are never reassigned. */
    public g2 crewById(int id) {
        if (id < 0 || id >= crewIds.size()) return null;
        g2 crew = crewIds.get(id);
        return crew != null && crew.y0 == fortress ? crew : null;
    }

    public int crewIdCount() { return crewIds.size(); }

    /** Called at deterministic simulation boundaries, never on UI selection. */
    void refreshCrewIds() {
        IdentityHashMap<g2, Boolean> live = new IdentityHashMap<>();
        for (int i = 0; i < fortress.X.a; i++) {
            g2 crew = crew(i);
            if (crew != null && crew.y0 == fortress) live.put(crew, Boolean.TRUE);
        }
        // Prune before registering new arrivals. Engine pooling can reuse a
        // dead soldier's Java object, but must not revive its old command ID.
        idsByCrew.entrySet().removeIf(entry -> {
            if (live.containsKey(entry.getKey())) return false;
            crewIds.set(entry.getValue(), null);
            return true;
        });
        for (int i = 0; i < fortress.X.a; i++) {
            g2 crew = crew(i);
            if (live.containsKey(crew) && !idsByCrew.containsKey(crew)) {
                idsByCrew.put(crew, crewIds.size());
                crewIds.add(crew);
            }
        }
    }

    /** How many crew this side has. */
    public int crewCount() {
        return fortress.X.a;
    }

    /** Rooms carrying at least one weapon record. */
    public int weaponRooms() {
        int n = 0;
        for (int i = 0; i < fortress.Q; i++) {
            v1 room = fortress.R[i];
            if (room != null && room.L0 != null && room.L0.length > 0) {
                n++;
            }
        }
        return n;
    }

    /** Total remaining structure across this side's rooms. */
    public float structure() {
        return structureOf(fortress);
    }

    /** Structure this side has taken off the opponent since the match started. */
    public float damageDealt() {
        return damageDealt;
    }

    /** Recompute {@link #damageDealt}. Called once a frame by the match. */
    void sampleDamage() {
        damageDealt = opponentStructureAtStart - structureOf(opponent.fortress);
    }

    private static float structureOf(s0 fortress) {
        float total = 0.0f;
        for (int i = 0; i < fortress.Q; i++) {
            v1 room = fortress.R[i];
            if (room != null) {
                total += room.X0;
            }
        }
        return total;
    }

    @Override
    public String toString() {
        return name + " (" + (isNear() ? "near" : "far") + ", "
               + fortress.x.d + ")";
    }
}
