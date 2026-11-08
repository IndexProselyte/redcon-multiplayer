package redcon.duel;

/**
 * Whoever is in charge of one fortress.
 *
 * <h2>The split already exists; it just has no name</h2>
 *
 * The shipped game has two commander objects, {@code a1.W0} bound to
 * {@code a1.U0} and {@code a1.X0} bound to {@code a1.V0}, and its update method
 * {@code a1.C} ticks <em>only</em> {@code X0}:
 *
 * <pre>
 * if (R0 == 1) {
 *     X0.p();
 *     ...
 * }
 * </pre>
 *
 * So the entire player/AI distinction in REDCON is "whose commander does not get
 * called". {@code W0} is constructed, bound and then never run, because the
 * fortress it is bound to is the one the player drives with the mouse. There is
 * no player class to generalise and no AI flag to flip - there is a call that
 * happens for one side and not the other.
 *
 * <p>That makes this interface a small change with a large consequence: a side
 * whose commander is an {@link AiCommander} gets {@code p()} called and plays
 * itself, and a side whose commander is a {@link HumanCommander} does not and
 * waits for orders. Two humans is two of the latter. Practice against the
 * computer is one of each, which is the shipped game with the sides relabelled.
 */
public interface Commander {

    /** Whatever this commander is called, for the HUD and the result screen. */
    String name();

    /**
     * Take charge of a side. Called once, after the battle is armed.
     *
     * <p>Binding is not free of history: {@code n3.q.t} only builds its
     * subsystems when they are null, so a commander re-bound in a second battle
     * keeps a think schedule measured against the first battle's clock and sits
     * silent for as long as that clock ran. {@link AiCommander} clears them.
     */
    void bind(Side side);

    /** The side this commander was bound to, or null before {@link #bind}. */
    Side side();

    /**
     * One frame's worth of decision making, called between the entity update
     * and the clock advance - the same place {@code a1.C} ticks {@code X0}.
     */
    void update();

    /** True if orders come from a person rather than from the game's own AI. */
    boolean isHuman();
}
