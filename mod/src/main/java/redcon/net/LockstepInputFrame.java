package redcon.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import redcon.duel.Order;

/** One commander's complete, ordered input for one deterministic frame. */
public final class LockstepInputFrame {
    // 31 header/checksum bytes + 8 * (9 frame bytes + 15 * 9 order bytes)
    // = 1183 bytes. Even a completely full redundant window fits the MTU.
    public static final int MAX_ORDERS = 15;

    public final int player;
    public final long frame;
    private final List<Order> orders;

    public LockstepInputFrame(int player, long frame, List<Order> orders) {
        DuelEvent.requirePlayer(player);
        if (frame < 0 || orders == null || orders.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("invalid lockstep input frame");
        }
        ArrayList<Order> copy = new ArrayList<>(orders.size());
        for (Order order : orders) {
            if (order == null) {
                throw new IllegalArgumentException("null lockstep order");
            }
            copy.add(order.copy());
        }
        this.player = player;
        this.frame = frame;
        this.orders = Collections.unmodifiableList(copy);
    }

    public List<Order> orders() {
        ArrayList<Order> copy = new ArrayList<>(orders.size());
        for (Order order : orders) copy.add(order.copy());
        return copy;
    }

    public boolean isEmpty() { return orders.isEmpty(); }
}
