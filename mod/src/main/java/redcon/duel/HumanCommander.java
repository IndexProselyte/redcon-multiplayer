package redcon.duel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A side driven by a person: no AI tick, just a queue of {@link Order}s.
 *
 * Orders are queued rather than applied the moment they arrive, and drained
 * once a frame from {@link #update}. That is not ceremony - it is what makes
 * the input source interchangeable. A mouse click, a scripted test, a hotseat
 * keyboard and (in Phase 04) a packet all put an order in the same queue, and
 * the simulation sees them at the same point in the frame regardless of where
 * they came from.
 *
 * <p>It also gives Phase 04 the seam it needs for free: the queue is exactly
 * the set of orders that would have to be sent to the peer this frame.
 */
public final class HumanCommander implements Commander {

    private final String name;
    private final Deque<Order> pending = new ArrayDeque<>();
    private final List<Order> applied = new ArrayList<>();
    private Side side;
    private int accepted;
    private int refused;

    public HumanCommander(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void bind(Side side) {
        this.side = side;
    }

    @Override
    public Side side() {
        return side;
    }

    /** Queue an order, to be carried out on the next frame. */
    public void give(Order order) {
        pending.add(order);
    }

    /** Queue several. */
    public void give(Order... orders) {
        for (Order order : orders) {
            pending.add(order);
        }
    }

    /**
     * Drain the queue into the simulation.
     *
     * Every queued order is attempted, including ones the engine refuses. A
     * refusal is a normal outcome - a weapon out of arc, a crew member with no
     * route - so it is counted rather than thrown.
     */
    @Override
    public void update() {
        while (!pending.isEmpty()) {
            Order order = pending.poll();
            if (order.apply(side)) {
                accepted++;
                applied.add(order);
            } else {
                refused++;
            }
        }
    }

    @Override
    public boolean isHuman() {
        return true;
    }

    /** Orders still waiting for the next frame. */
    public int queued() {
        return pending.size();
    }

    /** Orders the engine took. */
    public int accepted() {
        return accepted;
    }

    /** Orders the engine declined. */
    public int refused() {
        return refused;
    }

    /** Every order that took effect, in the order it did. */
    public List<Order> history() {
        return applied;
    }
}
