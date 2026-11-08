package redcon.desktop;

/**
 * REDCON's sub-screen list grammar, as used by the recovered OPTIONS activity.
 *
 * <p>The title menu ({@code p3.i}) and the difficulty chooser ({@code p3.e})
 * use the big right-aligned rows {@code f3.e.L}/{@code F} build. Screens that
 * carry content rather than a handful of top-level destinations use a second
 * idiom: the background scene dimmed by {@code f3.e.k}, the shell's own
 * top-left Back control from {@code f3.e.H}, and a centred column of
 * fixed-width rows whose left half is a label and right half a value or
 * control. {@code p3.f} is the reference; Browse and Pairing are the same kind
 * of screen, so they are built from the same calls with the same metrics
 * rather than approximated.</p>
 */
final class DuelMenu {

    /** Row width in {@code p3.f}'s label/value rows. */
    private static final int ROW_WIDTH = 573;

    /** The grey REDCON uses for unavailable list text. */
    static final int DISABLED = -8355712;

    private DuelMenu() {
    }

    /** Prepare an activity's row container as a centred settings-style column. */
    static void column(i3.b list) {
        f3.e.f0(list, 16, 0, 0, 0);
        list.v = new i3.l(0, 2);
    }

    /** A section heading, the weight {@code p3.f} gives MUSIC and SOUNDS. */
    static void heading(i3.b list, String text) {
        i3.g group = new i3.g();
        group.h = 8;
        group.j = 8;
        group.i = 24;
        group.f = 5;
        i3.g label = new i3.g();
        label.h = 0;
        label.j = 0;
        f3.e.K(f3.e.J(list, new i3.l(0, 2), group), label, text, h3.c.o);
    }

    /**
     * A label/value row. Pass a non-zero {@code event} to make the whole row a
     * native tap target; pass 0 for a read-only line.
     */
    static i3.b row(i3.b list, mojo.EventListener owner, int event,
                    String label, String value, int valueColour) {
        i3.g bounds = rowBounds();
        i3.g left = new i3.g();
        left.k = 8;
        left.f = 1;
        i3.g right = new i3.g();
        right.f = 2;

        // Single-line halves, as in p3.f's own rows: the recovered layout
        // measures wrapped text at its unwrapped height, so wrapping a row
        // label makes it spill into the row below instead of growing the row.
        i3.b row = f3.e.J(list, new i3.c(16, 1), bounds);
        f3.e.K(row, left, label, h3.c.o);
        if (value != null) {
            f3.e.O(row, value, h3.c.X, valueColour, right);
        }
        if (event != 0) {
            row.b = event;
            row.addListener(owner);
            row.v(true);
        }
        return row;
    }

    /**
     * A native value selector using the same arrow sprites and horizontal
     * control layout as REDCON's Options screen.
     */
    static i3.n choice(i3.b list, mojo.EventListener owner,
                       int previousEvent, int nextEvent,
                       String label, String value) {
        i3.g bounds = rowBounds();
        i3.g left = new i3.g();
        left.k = 8;
        left.f = 1;
        i3.g right = new i3.g();
        right.f = 2;

        i3.b row = f3.e.J(list, new i3.c(16, 1), bounds);
        f3.e.K(row, left, label, h3.c.o);

        i3.b selector = new i3.b();
        selector.v = new i3.l(16, 1);
        selector.l = right;

        i3.e previous = choiceButton(owner, previousEvent, "-");
        selector.x(previous);

        i3.g valueBounds = new i3.g();
        valueBounds.d = 88;
        i3.n displayed = f3.e.O(selector, value, h3.c.X, -1, valueBounds);
        displayed.w = 16;

        i3.e next = choiceButton(owner, nextEvent, "+");
        selector.x(next);

        row.x(selector);
        return displayed;
    }

    /** Compact text action; unlike h3.c.i it does not carry corner-button margins. */
    private static i3.e choiceButton(mojo.EventListener owner, int event,
                                     String text) {
        i3.e button = new i3.e();
        button.v = new i3.l(8, 1);
        i3.g bounds = new i3.g();
        bounds.d = 44;
        button.l = bounds;
        i3.n glyph = f3.e.O(button, text, h3.c.o, -1, new i3.g());
        glyph.w = 16;
        button.b = event;
        button.addListener(owner);
        return button;
    }

    /** A read-only line in REDCON's unavailable grey. */
    static void note(i3.b list, String text) {
        i3.g bounds = rowBounds();
        i3.g left = new i3.g();
        left.k = 8;
        left.f = 1;
        // f3.e.Q is the wrapping text builder; f3.e.O is not. A note is the one
        // line long enough that a verbose locale can push it off the canvas.
        f3.e.Q(f3.e.J(list, new i3.c(16, 1), bounds), text, h3.c.X, DISABLED, left);
    }

    /** The fixed-width, centred row box p3.f gives its label/control lines. */
    private static i3.g rowBounds() {
        i3.g bounds = new i3.g();
        bounds.d = ROW_WIDTH;
        bounds.b = 0;
        bounds.h = 8;
        bounds.j = 8;
        return bounds;
    }

    /** Seconds as the m:ss REDCON's own clocks use. */
    static String clock(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
