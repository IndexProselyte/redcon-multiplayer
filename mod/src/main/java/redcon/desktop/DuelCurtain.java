package redcon.desktop;

import mojo.Activity;

/** Opaque REDCON-font cover used for hotseat privacy and timed transitions. */
public final class DuelCurtain extends Activity {

    private static final int BACKGROUND = -15661037;
    private static final int PANEL = -14538461;
    private static final int RULE = -11184811;
    private static final int WHITE = -1;
    private static final int MUTED = -5592406;
    private static final int RED = -3354694;

    private final c3.h title;
    private final c3.h subtitle;
    private final c3.h detail;
    private final c3.h prompt;
    private boolean opaqueBackground = true;

    public DuelCurtain() {
        if (h3.c.j == null || h3.c.l == null || h3.c.m == null) {
            throw new IllegalStateException("REDCON fonts are not initialized");
        }
        title = h3.c.j.h();
        subtitle = h3.c.l.h();
        detail = h3.c.m.h();
        prompt = h3.c.m.h();
        title.l(WHITE);
        subtitle.l(RED);
        detail.l(MUTED);
        prompt.l(WHITE);
        show("DUEL", "", "", "");
    }

    public void show(String heading, String subheading, String body, String action) {
        opaqueBackground = true;
        setText(heading, subheading, body, action);
    }

    /** Draw the same REDCON panel without hiding the finished battle behind it. */
    public void showOverScene(String heading, String subheading, String body,
                              String action) {
        opaqueBackground = false;
        setText(heading, subheading, body, action);
    }

    private void setText(String heading, String subheading, String body,
                         String action) {
        title.o(0, heading);
        subtitle.o(0, subheading);
        detail.o(0, body);
        prompt.o(0, action);
    }

    @Override
    public void q() {
        int width = mojo.b.o;
        int height = mojo.b.p;
        if (opaqueBackground) {
            mojo.b0.b(BACKGROUND, 0, 0, width, height);
        }
        int panelWidth = Math.min(760, width - 64);
        int panelHeight = Math.min(360, height - 64);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        mojo.b0.b(PANEL, left, top, panelWidth, panelHeight);
        mojo.b0.b(RED, left, top, 6, panelHeight);
        mojo.b0.b(RULE, left + 40, top + 172, panelWidth - 80, 2);
        centered(title, width / 2, top + 42);
        centered(subtitle, width / 2, top + 112);
        centered(detail, width / 2, top + 210);
        centered(prompt, width / 2, top + panelHeight - 72);
    }

    private static void centered(c3.h label, int centerX, int top) {
        float scale = Math.max(1.0f, mojo.b.q);
        label.c(centerX - label.k() / (2.0f * scale), top, 48);
    }
}
