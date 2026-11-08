package redcon.net;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Attaches the live native battle adapter after Build has armed the screen.
 *
 * <h2>Why it holds events</h2>
 *
 * <p>The two devices arm their battle screens independently. The committed
 * {@code PHASE_CLOCK(BATTLE)} releases both, but one machine can finish
 * building REDCON's battle activity a second after the other has already
 * fired, and the shot that crosses the wire in that second is committed
 * whether or not this device is ready for it. Refusing it would leave the
 * product consumer a sequence behind the history and every later event would
 * then look like a gap - the duel would keep running and quietly stop
 * applying anything.</p>
 *
 * <p>So events that arrive early are held, in order, and delivered the moment
 * the adapter attaches. The queue is bounded: a device that never arms is not
 * going to catch up, and holding an unbounded battle's worth of orders to
 * prove it would only turn one failure into two.</p>
 */
public final class DuelBattleEventRouter implements DuelBattleEventConsumer {

    /** Roughly a minute of one fortress's battle events at a normal rate. */
    public static final int MAX_HELD_EVENTS = 4096;

    private final Queue<DuelEvent> held = new ArrayDeque<>();
    private DuelBattleEventConsumer delegate;
    private int dropped;

    public synchronized void attach(DuelBattleEventConsumer delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("battle event consumer is null");
        }
        if (this.delegate != null) {
            throw new IllegalStateException("battle event consumer is already attached");
        }
        this.delegate = delegate;
        while (!held.isEmpty()) {
            dispatch(held.remove());
        }
    }

    public synchronized void detach(DuelBattleEventConsumer expected) {
        if (delegate == expected) {
            delegate = null;
            held.clear();
        }
    }

    /** Battle events committed before this device armed its screen. */
    public synchronized int heldEvents() { return held.size(); }

    /** Held events discarded because the adapter never arrived. */
    public synchronized int droppedEvents() { return dropped; }

    @Override public synchronized void applyOrder(DuelEvent.OrderIssued order) {
        accept(order);
    }

    @Override public synchronized void applyFire(DuelEvent.Fire fire) {
        accept(fire);
    }

    @Override public synchronized void applyImpact(DuelEvent.Impact impact) {
        accept(impact);
    }

    @Override public synchronized void applyFortSync(
            DuelEvent.FortSync correction) {
        accept(correction);
    }

    @Override public synchronized String stateReport() {
        return delegate == null ? "" : delegate.stateReport();
    }

    private void accept(DuelEvent event) {
        if (delegate != null) {
            dispatch(event);
            return;
        }
        held.add(event);
        while (held.size() > MAX_HELD_EVENTS) {
            held.remove();
            dropped++;
        }
    }

    private void dispatch(DuelEvent event) {
        switch (event.kind()) {
            case ORDER:
                delegate.applyOrder((DuelEvent.OrderIssued) event);
                return;
            case FIRE:
                delegate.applyFire((DuelEvent.Fire) event);
                return;
            case IMPACT:
                delegate.applyImpact((DuelEvent.Impact) event);
                return;
            case FORT_SYNC:
                delegate.applyFortSync((DuelEvent.FortSync) event);
                return;
            default:
                throw new IllegalArgumentException(
                    "not a battle event: " + event.kind());
        }
    }
}
