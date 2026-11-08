package redcon.desktop;

/** Local-only castle marker shown over the controlled fortress for five seconds. */
final class DuelSeatMarker extends i3.q {
    static final long DURATION_NANOS = 5_000_000_000L;
    private long started = Long.MIN_VALUE;

    void update(i3.b root, int seat, long now) {
        if (started == Long.MIN_VALUE) started = now;
        if (!visible(now)) { s(); return; }
        if (!h()) root.x(this);
        int centre = seat == 0 ? mojo.b.o / 4 : mojo.b.o * 3 / 4;
        u(centre - 24, 103, 48, 36);
    }

    boolean visible(long now) {
        return started != Long.MIN_VALUE && now - started < DURATION_NANOS;
    }

    @Override public void n() {
        int gold = 0xffffcb78;
        mojo.b0.b(gold, g + 6, h + 12, 36, 24);
        for (int x = 0; x < 4; x++) {
            mojo.b0.b(gold, g + 6 + x * 10, h + 4, 6, 10);
        }
        mojo.b0.b(0xff192025, g + 20, h + 24, 8, 12);
    }
}
