package redcon.desktop;

/** Small inspection helpers for driving recovered native widgets in checks. */
final class NativeUi {

    private NativeUi() {
    }

    static i3.q findEvent(i3.q node, int event) {
        if (node == null) {
            return null;
        }
        if (node.b == event) {
            return node;
        }
        if (node instanceof i3.b) {
            i3.b group = (i3.b) node;
            for (int i = 0; i < group.t; i++) {
                i3.q found = findEvent(group.u[i], event);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * True when a pointer sample lands on an interactive widget of this tree
     * rather than on the screen behind it. This mirrors the walk {@code i3.j}
     * performs in its own {@code o}/{@code p} handlers.
     */
    static boolean hitsWidget(i3.j root, int x, int y) {
        i3.q found = root.f(x, y);
        while (found != null && !found.j()) {
            found = found.k;
        }
        return found != null && found != root;
    }

    static int centerX(i3.q widget) {
        // i3.b.f forwards screen coordinates unchanged while descending; its
        // child rectangles are therefore already in canvas coordinates.
        return widget.g + widget.i / 2;
    }

    static int centerY(i3.q widget) {
        return widget.h + widget.j / 2;
    }
}
