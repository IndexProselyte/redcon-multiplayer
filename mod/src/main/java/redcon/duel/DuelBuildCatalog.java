package redcon.duel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import o3.i1;
import o3.j1;
import o3.k1;
import o3.m1;
import o3.n1;
import o3.z0;

/**
 * REDCON's authored fortress-builder roster and its original credit costs.
 *
 * <p>The content has seven {@link k1} roots, but each root links to geometry
 * variants through {@code k1.l}. Flattening those chains yields the 23 choices
 * shown by the shipped CUSTOMIZE screen. The 13 {@code fort_setup#s*} records
 * are campaign loadouts, not chassis definitions.
 */
public final class DuelBuildCatalog {

    /** The campaign's starting purse and the first duel balance baseline. */
    public static final int DEFAULT_CREDIT_BUDGET = 2000;

    public static final class Entry {
        private final int index;
        private final int family;
        private final int variant;
        private final int itemId;
        private final FortDesign design;

        private Entry(int index, int family, int variant, k1 item) {
            this.index = index;
            this.family = family;
            this.variant = variant;
            this.itemId = item.f;
            this.design = FortDesign.capture(item.a());
        }

        public int index() {
            return index;
        }

        public int family() {
            return family;
        }

        public int variant() {
            return variant;
        }

        /** Inventory id used by the original builder widget. */
        public int itemId() {
            return itemId;
        }

        public String name() {
            return design.name();
        }

        public int chassisCost() {
            return unsigned(design.instantiate().g);
        }

        public FortDesign design() {
            return design.copy();
        }

        @Override
        public String toString() {
            return name() + " / " + (variant + 1) + " ($" + chassisCost() + ")";
        }
    }

    public static final class Cost {
        public final int chassis;
        public final int rooms;
        public final int towers;

        private Cost(int chassis, int rooms, int towers) {
            this.chassis = chassis;
            this.rooms = rooms;
            this.towers = towers;
        }

        public int total() {
            return chassis + rooms + towers;
        }

        /** Credits paid from the duel purse; choosing a chassis is free. */
        public int equipment() {
            return rooms + towers;
        }

        public int remaining(int budget) {
            return budget - equipment();
        }

        @Override
        public String toString() {
            return "$" + total() + " (chassis " + chassis
                   + ", rooms " + rooms + ", towers " + towers + ")";
        }
    }

    private final i1 inventory;
    private final List<Entry> entries;

    public DuelBuildCatalog(i1 inventory) {
        if (inventory == null || inventory.e == null) {
            throw new IllegalArgumentException("builder inventory is missing");
        }
        this.inventory = inventory;
        List<Entry> found = new ArrayList<>();
        for (int family = 0; family < inventory.e.length; family++) {
            int variant = 0;
            for (k1 item = inventory.e[family]; item != null; item = item.l) {
                found.add(new Entry(found.size(), family, variant++, item));
            }
        }
        entries = Collections.unmodifiableList(found);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(int index) {
        return entries.get(index);
    }

    public int size() {
        return entries.size();
    }

    /** Upgrade an independent editor design; never alter the authored content. */
    public FortDesign maximumTiers(FortDesign design) {
        z0 fort = design.instantiate();
        for (int room = 0; room < fort.n; room++) {
            int at = room * 5;
            m1 item = roomItem(unsigned(fort.o[at + 2]) | (fort.o[at + 1] << 16));
            if (item == null) throw new IllegalArgumentException("unknown room in " + design.name());
            while (item.m != null) item = item.m;
            fort.o[at + 1] = item.h;
            fort.o[at + 2] = item.g;
        }
        return design.captureEdited(fort);
    }

    /** Price an existing or edited design with the game's content costs. */
    public Cost cost(FortDesign design) {
        if (design == null) {
            throw new IllegalArgumentException("design is null");
        }
        z0 fort = design.instantiate();
        int roomCost = 0;
        for (int room = 0; room < fort.n; room++) {
            int at = room * 5;
            if (fort.o == null || at + 2 >= fort.o.length) {
                throw new IllegalArgumentException("truncated room layout in " + design.name());
            }
            int contentId = unsigned(fort.o[at + 2]) | (fort.o[at + 1] << 16);
            m1 item = roomItem(contentId);
            if (item == null) {
                throw new IllegalArgumentException(
                    "unknown room " + Integer.toUnsignedString(contentId)
                    + " in " + design.name());
            }
            roomCost += item.i ^ item.q;
        }

        int towerCost = 0;
        for (int tower = 0; tower < fort.p; tower++) {
            int at = tower * 2;
            if (fort.q == null || at + 1 >= fort.q.length) {
                throw new IllegalArgumentException("truncated tower layout in " + design.name());
            }
            n1 item = towerItem(unsigned(fort.q[at + 1]));
            if (item == null) {
                throw new IllegalArgumentException(
                    "unknown tower " + unsigned(fort.q[at + 1])
                    + " in " + design.name());
            }
            towerCost += item.h ^ item.j;
        }
        return new Cost(unsigned(fort.g), roomCost, towerCost);
    }

    private m1 roomItem(int contentId) {
        for (j1 item : inventory.g) {
            if (item instanceof m1 && item.e == contentId) {
                return (m1) item;
            }
        }
        return null;
    }

    private n1 towerItem(int objectId) {
        for (n1 item : inventory.c) {
            if (unsigned(item.g) == objectId) {
                return item;
            }
        }
        return null;
    }

    private static int unsigned(short value) {
        return value & 0xffff;
    }
}
