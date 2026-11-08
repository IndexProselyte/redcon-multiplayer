package redcon.duel;

import o3.a1;
import o3.g2;
import o3.s0;
import o3.v1;

/**
 * Turning a point on the screen into "whose room is that".
 *
 * <h2>One world, two windows onto it</h2>
 *
 * Both fortresses live in a single world coordinate space and the screen shows
 * two separate windows into it, side by side. {@code a1.B0} is the mapping, and
 * it branches on which half of the screen the point is in:
 *
 * <pre>
 * worldX = screenX &lt; width/2 ? c1 + screenX
 *                            : 2200 - (width - (e1 + screenX))
 * </pre>
 *
 * so the left half scrolls over the near fortress and the right half over the
 * far one, with the two camera offsets kept independently. {@code C0} does the
 * same for y. Once through that mapping, both fortresses are addressed the same
 * way, and {@code s0.U(worldX, worldY)} undoes each fortress's own placement:
 *
 * <pre>
 * cellX = ((worldX - G) * z) / 96
 * cellY = (worldY - H) / 96
 * </pre>
 *
 * <p>The {@code * z} is the whole of the left/right asymmetry - the far fortress
 * has {@code z == -1} and so counts its cells leftward from its origin. Nothing
 * else differs, which is why one picking routine serves either commander and
 * why {@code DuelProbe} finds every occupied cell on both fortresses
 * round-tripping through the same code.
 *
 * <h2>Why not just call the engine's picker</h2>
 *
 * {@code a1.n()} and {@code a1.n0()} already do this, but they resolve
 * ownership against {@code U0} - "my room" is a room of {@code U0}, always, and
 * the crew whose selection is legal are {@code U0}'s. That is correct for a
 * single-player game and wrong for the far commander in a duel. This class asks
 * the same questions relative to a {@link Side} instead, and leaves the
 * engine's handlers alone.
 */
public final class Picking {

    /** What was under the cursor. */
    public static final class Pick {
        /** The side that owns the room, or null if nothing was hit. */
        public final Side owner;
        /** The room, or null. */
        public final v1 room;
        /** Its index within {@link #owner}, or -1. */
        public final int roomIndex;
        /** World coordinates the pick was resolved at. */
        public final int worldX;
        public final int worldY;

        Pick(Side owner, v1 room, int roomIndex, int worldX, int worldY) {
            this.owner = owner;
            this.room = room;
            this.roomIndex = roomIndex;
            this.worldX = worldX;
            this.worldY = worldY;
        }

        public boolean hit() {
            return room != null;
        }

        /** True if this room belongs to the side doing the looking. */
        public boolean belongsTo(Side viewer) {
            return owner == viewer;
        }

        @Override
        public String toString() {
            if (!hit()) {
                return "nothing at (" + worldX + "," + worldY + ")";
            }
            // O0 is the room's name; R() is its several-paragraph description.
            return owner.name + " room " + roomIndex + " (" + room.O0 + ")";
        }
    }

    /**
     * Resolve a screen point against both fortresses.
     *
     * Needs the battle's camera to have been built ({@code a1.B()}), because
     * {@code B0} and {@code C0} read its offsets. Before that every point maps
     * to the same place.
     */
    public static Pick atScreen(DuelMatch match, int screenX, int screenY) {
        int worldX = a1.B0(screenX);
        int worldY = a1.C0(screenX, screenY);
        return atWorld(match, worldX, worldY);
    }

    /**
     * Resolve world coordinates against both fortresses.
     *
     * The two grids do not overlap - the near fortress sits near x=112 and the
     * far one near x=2184 - so asking both and taking whichever answers is
     * unambiguous, and does not depend on the screen-half rule that got us
     * here.
     */
    public static Pick atWorld(DuelMatch match, int worldX, int worldY) {
        Pick near = against(match.near(), worldX, worldY);
        if (near.hit()) {
            return near;
        }
        return against(match.far(), worldX, worldY);
    }

    private static Pick against(Side side, int worldX, int worldY) {
        v1 room = side.fortress.U(worldX, worldY);
        if (room == null) {
            return new Pick(null, null, -1, worldX, worldY);
        }
        return new Pick(side, room, side.indexOf(room), worldX, worldY);
    }

    /**
     * A point inside this room, in world coordinates - the inverse of
     * {@link #atWorld}.
     *
     * Derived from the fortress grid rather than from the room, because the
     * room's own position fields do not survive the round trip. {@code v1.d0}
     * stores {@code v0} as the horizontal <em>centre</em> and {@code w0} as the
     * <em>floor line</em> - the bottom edge, one cell past the room - so feeding
     * them back into {@code s0.U}, which floors to a cell, lands underneath the
     * room rather than in it. Two other traps in the same neighbourhood:
     * {@code v1.G} and {@code v1.H} are a sprite anchor and not a grid cell, and
     * the far fortress mirrors x about its origin.
     *
     * <p>Walking the grid sidesteps all three, and it is the mapping
     * {@code DuelProbe} measures round-tripping on every occupied cell of both
     * fortresses.
     */
    public static int[] worldCentreOf(v1 room) {
        s0 fortress = room.u0;
        for (int gy = 0; gy < fortress.J; gy++) {
            for (int gx = 0; gx < fortress.I; gx++) {
                if (fortress.N(gx, gy) == room) {
                    return new int[] {
                        fortress.G + fortress.z * (gx * 96 + 48),
                        fortress.H + gy * 96 + 48
                    };
                }
            }
        }
        return new int[] { room.v0, room.w0 };
    }

    /**
     * The crew member nearest a world x on this side, or null.
     *
     * {@code a1.s0} does the same scan for the player's fortress: walk the crew,
     * skip anyone already picked, and keep the smallest horizontal distance.
     * Crew positions are {@code g2.B0} and {@code g2.C0}.
     */
    public static g2 crewNear(Side side, int worldX, int worldY) {
        g2 best = null;
        float bestDistance = Float.MAX_VALUE;
        Object[] items = (Object[]) side.fortress.d0.b;
        if (items == null) {
            return null;
        }
        for (int i = 0; i < side.fortress.d0.a; i++) {
            g2 crew = (g2) items[i];
            if (crew == null || !crew.A()) {
                continue;
            }
            float dx = Math.abs(worldX - crew.B0);
            float dy = Math.abs(worldY - crew.C0);
            // Crew are about a room tall and stand on the floor of one, so a
            // generous vertical window and a tight horizontal one matches how
            // the game picks them.
            if (dy > 48 || dx > 24) {
                continue;
            }
            if (dx < bestDistance) {
                bestDistance = dx;
                best = crew;
            }
        }
        return best;
    }

    private Picking() {
    }
}
