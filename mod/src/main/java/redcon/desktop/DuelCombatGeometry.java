package redcon.desktop;

/**
 * The playfield the duel's simulation measures against, instead of the canvas.
 *
 * <h2>What was wrong</h2>
 *
 * Four decisions inside REDCON's combat read the logical canvas:
 *
 * <ul>
 *   <li>{@code o0.C} switches a retargeted missile between a ballistic arc and
 *       direct homing at {@code a1.a1 / 2};</li>
 *   <li>{@code d2.M} gives an active-defense shot exactly enough life to reach
 *       {@code (a1.a1 / 2) + 32};</li>
 *   <li>{@code l0.o} sets the troop ICBM's apex to {@code (-a1.b1) - u0};</li>
 *   <li>{@code p0.p} deletes a zero-gravity shot, and zeroes its damage, once
 *       it passes {@code a1.d1 - 32}.</li>
 * </ul>
 *
 * <p>None of those is drawing. They decide how far a turret reaches, which way
 * a scrambled missile flies, how long twenty soldiers are in the air, and
 * whether a shell does damage at all.</p>
 *
 * <h2>Why a fixed playfield rather than the canvas</h2>
 *
 * The canvas is not the window. {@code mojo.Manifest.a} negotiates it from the
 * device's physical diagonal, aspect ratio and density against a three-row
 * table, and the answer is not the same on two devices:
 *
 * <pre>
 *   1280x720  @2.000  window -&gt; canvas 1280x720   (this desktop port)
 *   2400x1080 @2.625  window -&gt; canvas 1560x702
 *   2340x1080 @2.750  window -&gt; canvas 1560x720
 *   2412x1080 @2.750  window -&gt; canvas 1600x716
 *   1920x1080 @3.000  window -&gt; canvas 1600x900
 *   2960x1440 @4.000  window -&gt; canvas 1560x758
 * </pre>
 *
 * <p>Width is clamped to 1560 for anything taller than 2:1 and to 1600 for
 * 16:9, so the desktop's 1280 disagrees with every handset. Height is not
 * clamped at all in that range, so two <em>handsets</em> disagree with each
 * other: 702, 716, 720 and 758 are all real answers.</p>
 *
 * <h2>The values, and why these</h2>
 *
 * REDCON shipped on handsets. The desktop port is the newcomer, and its
 * narrower canvas is what deviates - it quietly shortens its own turret reach
 * by about a sixth. So the duel uses the shipped handset geometry on both
 * devices rather than either device's own canvas:
 *
 * <ul>
 *   <li>{@link #WIDTH} 1560 is the manifest's clamp for every aspect ratio
 *       above 2:1, which is every phone the game is played on now. It is the
 *       width a handset actually gets, not a number chosen to be large.</li>
 *   <li>{@link #HEIGHT} 720 sits inside the same row's 700..900 clamp, is what
 *       a 19.5:9 handset lands on exactly, and is what this desktop port
 *       already uses - so it changes the fewest trajectories of any value in
 *       the legal range.</li>
 * </ul>
 *
 * <p>This is a deliberate balance decision and not a neutral one: on the
 * desktop, turret reach and the missile switchover move outwards to match the
 * handset build, and on handsets the troop ICBM's apex is pinned instead of
 * following the screen. Both commanders in a duel get the same rules, which is
 * the property that was missing.</p>
 *
 * <h2>Scope</h2>
 *
 * Duel only. Every accessor falls through to the live static outside a duel,
 * so the campaign keeps the behaviour it shipped with on whatever device is
 * running it. The call sites are rewritten by
 * {@code tools/recovery/callsites.py} and its Android counterpart, which
 * replace a {@code getstatic} with a same-size, same-stack-effect call to the
 * accessors here.
 */
public final class DuelCombatGeometry {

    /** The playfield width a duel measures against. See the class comment. */
    public static final int WIDTH = 1560;

    /** The playfield height a duel measures against. See the class comment. */
    public static final int HEIGHT = 720;

    /**
     * Stands in for {@code a1.a1} at the two width-derived simulation sites.
     */
    public static int width() {
        return GameContext.isDuel() ? WIDTH : o3.a1.a1;
    }

    /**
     * Stands in for {@code a1.b1} at the troop ICBM's apex.
     */
    public static int height() {
        return GameContext.isDuel() ? HEIGHT : o3.a1.b1;
    }

    /**
     * Stands in for {@code a1.d1} where {@code p0.p} culls a straight shot.
     *
     * <p>{@code a1.d1} is the near fortress's camera bound, set in
     * {@code a1.F0} to {@code -canvasHeight + U0.K()}. {@code s0.K} returns
     * zero for every canvas at or below 900 tall, which is every canvas the
     * manifest hands out, so the camera bound is just the negated canvas
     * height - and this returns the negated playfield height in its place.</p>
     *
     * <p>Culling a straight shot that has left the playfield upwards is sound:
     * with no gravity it never comes back. What was not sound was the two
     * devices disagreeing about where that is, because the cull also zeroes
     * the shot's damage.</p>
     */
    public static float ceiling() {
        return GameContext.isDuel() ? -HEIGHT : o3.a1.d1;
    }

    private DuelCombatGeometry() { }
}
