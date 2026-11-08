package redcon.duel;

import n3.q;

/**
 * The game's own AI, kept behind {@link Commander} so it stays available.
 *
 * The scope asks for the AI to be stubbed out behind an interface rather than
 * removed, so practice matches keep working. That turns out to cost almost
 * nothing, because {@code n3.q} is already commander-shaped: two fields for the
 * two fortresses, five subsystem objects, and exactly two methods -
 * {@code t(s0)} to bind and {@code p()} to think. There is no rendering in it
 * and no reference to a screen, so it runs headlessly as it stands.
 *
 * <pre>
 * public final class n3.q extends o3.i0 {
 *   public o3.s0 t;    // the fortress it commands
 *   public o3.s0 u;    // the fortress it is fighting
 *   public n3.c v; ... // target picking, crew, repair, perks
 *   public final void p();      // think
 *   public final void t(o3.s0); // bind
 * }
 * </pre>
 */
public final class AiCommander implements Commander {

    private final q brain;
    private final String name;
    private Side side;

    /**
     * @param brain the engine's commander for this side - {@code a1.W0} for the
     *              near fortress, {@code a1.X0} for the far one. They are
     *              {@code static final} and there are only ever two.
     */
    public AiCommander(q brain, String name) {
        this.brain = brain;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void bind(Side side) {
        this.side = side;
        // t() builds the subsystems only when they are null, so a commander
        // reused across battles would keep a next-think time measured against
        // the previous battle's clock and stay silent for as long as that clock
        // ran. Clearing one forces the rebuild.
        brain.v = null;
        brain.t(side.fortress);
    }

    @Override
    public Side side() {
        return side;
    }

    @Override
    public void update() {
        brain.p();
    }

    @Override
    public boolean isHuman() {
        return false;
    }

    /** The engine object behind this commander, for harnesses that inspect it. */
    public q brain() {
        return brain;
    }
}
