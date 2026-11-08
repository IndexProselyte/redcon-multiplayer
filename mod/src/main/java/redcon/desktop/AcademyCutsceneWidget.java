package redcon.desktop;

import mojo.Platform;

/**
 * REDCON's campaign cutscene widget with one deliberately small extension:
 * speaker {@value #NARRATOR} draws the normal dialogue plate without a
 * character portrait. All other lines follow {@code q3.j} exactly.
 */
final class AcademyCutsceneWidget extends i3.q {

    static final short NARRATOR = -1;

    static final int EVENT_SKIPPED = 1;
    static final int EVENT_FINISHED = 2;
    private static final int[] PORTRAITS = {7, 10, 9, 8, 11};
    private static final int[] PORTRAIT_SIDES = {16, 16, 2, 1, 16};
    private static final int FADE_TICKS = 2640;
    private static final int INPUT_DELAY_TICKS = 5280;

    private final mojo.t skipHand;
    private final c3.h text;

    private j3.b script;
    private int suppressedSpeakers;
    private int lineIndex;
    private int state;
    private int stateStarted;
    private int inputEnabledAt;
    private int speaker = Integer.MIN_VALUE;
    private int plateY;
    private int plateHeight;
    private int textCenterOffset;
    private int portraitX;
    private int portraitTint;
    private int portraitHighlight;
    private g3.k portrait;
    private j3.f plate;
    private boolean showSkip;
    private boolean disposed;

    AcademyCutsceneWidget() {
        v(true);
        this.l = i3.g.p;
        text = h3.c.m.h();
        text.i = m3.a.d;
        skipHand = mojo.t.o.b(11);
    }

    void start(j3.b value, int suppressed) {
        script = value;
        suppressedSpeakers = suppressed;
        lineIndex = -1;
        speaker = Integer.MIN_VALUE;
        showSkip = ((q3.w[]) value.d).length > 1;
        u(0, 0, mojo.b.o, mojo.b.p);
        show(nextLine());
        m3.h.e(7);
    }

    j3.b script() {
        return script;
    }

    int currentLine() {
        return lineIndex;
    }

    private void show(q3.w line) {
        c3.c font = text.g;
        String authored = line.f;
        boolean hasBreak = authored.indexOf('\n') != -1;
        String wrapped = font.q(560, authored);
        text.o(80, wrapped);
        textCenterOffset = ((int) (text.l + text.n)) / 2;
        plateHeight = text.f() + 40;
        if (!hasBreak) {
            plateHeight += font.k() * 2;
        } else if (wrapped.endsWith("\n")) {
            plateHeight += font.k();
        }
        plate = m3.h.c(600, plateHeight);

        int nextSpeaker = line.c;
        boolean changed = nextSpeaker != speaker;
        speaker = nextSpeaker;
        portraitTint = line.d;
        portraitHighlight = line.e;
        portrait = null;
        portraitX = 0;

        int top = Math.max(((mojo.b.p - 256) / 2) - 356, 0);
        plateY = top + 356;
        if (nextSpeaker >= 0) {
            portrait = g3.k.n[PORTRAITS[nextSpeaker]];
            short[] geometry = portrait.a;
            if (geometry.length / 6 < 3) {
                portraitTint = 0;
                portraitHighlight = 0;
            }
            int side = PORTRAIT_SIDES[nextSpeaker];
            int part = Math.min(0, (geometry.length / 6) - 1);
            short[] atlas = g3.k.m.e;
            int at = part * 6;
            int x = geometry[at] - geometry[at + 3];
            int sprite = geometry[at + 2] * 4;
            int right = x + atlas[sprite + 2];
            if (side == 1) {
                portraitX = -280 - x;
            } else if (side == 2) {
                portraitX = 300 - right;
            }
            // The tutorial voice sits higher, but only when there is no skip
            // hand in the corner to collide with. q3.j makes the same test.
            if (nextSpeaker == 4 && !showSkip) {
                plateY = top + 292;
            }
        }

        stateStarted = mojo.b.t;
        if (changed) {
            inputEnabledAt = stateStarted;
        }
        state = changed ? 3 : 2;
    }

    private q3.w nextLine() {
        short platform = Platform.d0(5) ? (short) 1 : (short) 2;
        q3.w[] lines = (q3.w[]) script.d;
        while (++lineIndex < lines.length) {
            q3.w line = lines[lineIndex];
            boolean suppressed = line.c >= 0
                && ((1 << line.c) & suppressedSpeakers) != 0;
            if (!suppressed && (platform & line.b) == line.b) {
                return line;
            }
        }
        return null;
    }

    @Override
    public void n() {
        int elapsed = mojo.b.t - stateStarted;
        int alpha = state == 3
            ? c1.a.B(0, 191, elapsed, FADE_TICKS)
            : state == 4 ? c1.a.B(191, 0, elapsed, FADE_TICKS) : 255;
        int color = mojo.j.g(alpha);
        int center = mojo.b.o / 2;

        plate.b = color;
        plate.c(center - 300, plateY, 0);
        if (portrait != null) {
            int x = center + portraitX;
            int y = plateY + 1;
            portrait.h = 0;
            portrait.i = color;
            portrait.a(x, y);
            if (portraitTint != 0) {
                portrait.h = 1;
                portrait.i = portraitTint;
                portrait.a(x, y);
            }
            if (portraitHighlight != 0) {
                portrait.h = 2;
                portrait.i = portraitHighlight;
                portrait.a(x, y);
            }
        }

        int textY = ((plateHeight / 2) + plateY) - textCenterOffset;
        text.l(color);
        text.c(center, textY, 20);
        if (showSkip) {
            int inset = h3.c.r;
            skipHand.c(mojo.b.o - inset, mojo.b.p - inset, 10);
        }
    }

    @Override
    public void k(int key) {
        if (key == 3 || key == 13 || key == 44) {
            beginAdvance();
        }
    }

    @Override
    public void p(int x, int y) {
        // The original widget advances on release, not press.
    }

    @Override
    public void q(int x, int y) {
        if (mojo.b.t < inputEnabledAt + INPUT_DELAY_TICKS) {
            return;
        }
        if (showSkip && x >= mojo.b.o - 128 && y >= mojo.b.p - 128) {
            close();
            dispatchEvent(EVENT_SKIPPED, null);
            mojo.b.i.m();
            return;
        }
        beginAdvance();
    }

    private void beginAdvance() {
        if (state == 2) {
            state = 4;
            stateStarted = mojo.b.t;
            m3.g.h(573, 0);
        }
    }

    @Override
    public void screenChanged() {
        u(0, 0, mojo.b.o, mojo.b.p);
    }

    @Override
    public void w() {
        int elapsed = mojo.b.t - stateStarted;
        if (state == 3 && elapsed >= FADE_TICKS) {
            stateStarted = mojo.b.t;
            state = 2;
            // 574 is dialog_1..4, the speech blip. q3.j plays it for every
            // speaker below 4 - the tutorial voice is silent because nobody is
            // on screen saying it, which is exactly the narrator's case here.
            if (speaker >= 0 && speaker < 4) {
                m3.g.h(574, 0);
            }
            return;
        }
        if (state == 4 && elapsed >= FADE_TICKS) {
            q3.w line = nextLine();
            if (line != null) {
                show(line);
            } else {
                close();
                dispatchEvent(EVENT_FINISHED, null);
            }
        }
    }

    void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        s();
        if (h3.c.W) {
            text.dispose();
        }
    }
}
