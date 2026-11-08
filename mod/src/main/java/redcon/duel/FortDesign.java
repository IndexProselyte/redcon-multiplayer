package redcon.duel;

import java.util.Arrays;
import mojo.f;
import o3.z0;

/**
 * An independent snapshot of one fortress design.
 *
 * <p>The game's {@link z0} is both a content record and the mutable save format
 * used by the campaign customizer. That is harmless with one player and one
 * current fortress, but a duel may have two players editing the same chassis at
 * the same time. Sharing the content object would let one edit overwrite the
 * other. A {@code FortDesign} therefore owns a deep copy of every mutable array
 * in the blueprint and hands out another copy whenever a battle needs it.
 *
 * <p>Object references inside the record, notably the sprite geometry, remain
 * shared. They are immutable content. The room, tower, perk, and derived-grid
 * arrays are copied.
 */
public final class FortDesign {

    private final int blueprintId;
    private final z0 snapshot;

    private FortDesign(int blueprintId, z0 snapshot) {
        this.blueprintId = blueprintId;
        this.snapshot = snapshot;
    }

    /** Capture a design without retaining any of its mutable arrays. */
    public static FortDesign capture(z0 blueprint) {
        if (blueprint == null) {
            throw new IllegalArgumentException("blueprint is null");
        }
        return new FortDesign(findObjectId(blueprint), copyOf(blueprint));
    }

    /** The index in {@code mojo.f.c}, or -1 for a runtime-created blueprint. */
    public int blueprintId() {
        return blueprintId;
    }

    public String name() {
        return snapshot.d == null ? "Unnamed fortress" : snapshot.d;
    }

    public int roomCount() {
        return snapshot.n;
    }

    /** A battle/customizer copy. The caller may mutate it freely. */
    public z0 instantiate() {
        return copyOf(snapshot);
    }

    /** Another independent snapshot, used for commit and load-last. */
    public FortDesign copy() {
        return new FortDesign(blueprintId, copyOf(snapshot));
    }

    /**
     * Capture an edited instance while retaining the original content-table id.
     * The editor works on {@link #instantiate} copies, so identity lookup alone
     * cannot recover that id afterwards.
     */
    public FortDesign captureEdited(z0 edited) {
        if (edited == null) {
            throw new IllegalArgumentException("edited blueprint is null");
        }
        return new FortDesign(blueprintId, copyOf(edited));
    }

    /** Stable enough for local tests and, later, build-commit diagnostics. */
    public int layoutHash() {
        int result = blueprintId;
        result = 31 * result + snapshot.n;
        result = 31 * result + Arrays.hashCode(snapshot.o);
        result = 31 * result + snapshot.p;
        result = 31 * result + Arrays.hashCode(snapshot.q);
        result = 31 * result + Arrays.hashCode(snapshot.r);
        result = 31 * result + Arrays.hashCode(snapshot.w);
        return result;
    }

    private static int findObjectId(z0 blueprint) {
        if (f.c == null) {
            return -1;
        }
        for (int i = 0; i < f.c.length; i++) {
            if (f.c[i] == blueprint) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Copy all z0 state explicitly. This list is intentionally boring: it
     * makes an added game field visible at compile/review time instead of
     * hiding the save boundary behind reflection.
     */
    private static z0 copyOf(z0 source) {
        z0 copy = new z0();
        copy.A = clone(source.A);
        copy.d = source.d;
        copy.e = source.e;
        copy.f = source.f;
        copy.g = source.g;
        copy.h = source.h;
        copy.i = source.i;
        copy.j = source.j;
        copy.k = source.k;
        copy.l = source.l;
        copy.m = source.m;
        copy.n = source.n;
        copy.o = clone(source.o);
        copy.p = source.p;
        copy.q = clone(source.q);
        System.arraycopy(source.r, 0, copy.r, 0,
                         Math.min(source.r.length, copy.r.length));
        copy.s = source.s;
        copy.t = source.t;
        copy.u = source.u;
        copy.v = source.v;
        copy.w = clone(source.w);
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        return copy;
    }

    private static int[] clone(int[] value) {
        return value == null ? null : value.clone();
    }

    private static short[] clone(short[] value) {
        return value == null ? null : value.clone();
    }

    private static byte[] clone(byte[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public String toString() {
        return name() + " [" + roomCount() + " rooms, "
               + Integer.toUnsignedString(layoutHash(), 16) + "]";
    }
}
