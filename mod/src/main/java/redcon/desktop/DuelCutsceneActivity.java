package redcon.desktop;

import mojo.EventListener;

/**
 * The Academy intro, played through REDCON's own cutscene widget.
 *
 * <p>Nothing here draws a cutscene. {@code AcademyCutsceneWidget} is
 * {@code q3.j} — the widget the campaign map raises through {@code q3.b.S} and
 * the customizer through {@code o3.b.V1} — with one value added to its speaker
 * field, {@code -1}, that draws the shipped plate with nobody on it. This
 * activity only gives that widget a screen to live on and somewhere to go when
 * it ends.</p>
 *
 * <h2>The lifecycle, which is the widget's and not ours</h2>
 *
 * <p>{@code start(script, mask)} loads the script and sizes the widget to the
 * canvas; adding it to a widget tree and calling {@code t()} starts it. It then
 * owns its own advance: a tap moves to the next line, a tap in the bottom-right
 * corner skips, and running out of lines ends it. It removes itself in all three
 * cases and reports which by event — {@code 1} for the corner, {@code 2} for the
 * end — so both are handled the same way here: the intro is over, go to the
 * Academy map.
 * The mask suppresses speakers by index; nothing is suppressed here, because
 * every speaker in the script — the narration plates, Kranz and Grimm — is the
 * point.</p>
 */
public final class DuelCutsceneActivity extends mojo.Activity {

    /** Show every speaker: a mask bit suppresses the speaker of that index. */
    private static final int SUPPRESS_NOTHING = 0;

    /** The exact Academy map that remains after the introductory beat. */
    private final AcademyMapActivity map = new AcademyMapActivity();
    private final AcademyCutsceneWidget cutscene = new AcademyCutsceneWidget();
    private final j3.b script;
    private boolean left;

    // mojo.EventListener is a class in the recovered engine, not an interface,
    // so the listener is its own object rather than something this class can
    // implement.
    private final EventListener onCutsceneEnded = new EventListener() {
        @Override
        public void handleEvent(int event, Object source, Object data) {
            if (event == AcademyCutsceneWidget.EVENT_SKIPPED
                    || event == AcademyCutsceneWidget.EVENT_FINISHED) {
                leave();
            }
        }
    };

    DuelCutsceneActivity(j3.b script) {
        this.script = script;
    }

    /**
     * Play the intro if this install has not seen it, else go straight on.
     *
     * <p>The flag is written when the intro is raised rather than when it ends,
     * so a player who quits mid-cutscene is not shown it again on next launch.
     * Being shown it once and stopping is a choice; being shown it forever is a
     * bug.</p>
     */
    static void openMultiplayer() {
        j3.b intro = DuelIntroFlag.seen() ? null : KranzIntro.script();
        if (intro == null) {
            mojo.b.i.d(DuelNameActivity.before(new AcademyMapActivity()));
            return;
        }
        DuelIntroFlag.markSeen();
        mojo.b.i.d(new DuelCutsceneActivity(intro));
    }

    @Override
    public void B() {
        map.B();
        // Keep the world and its Empire markers visible, but suppress controls
        // while q3.j owns input. In particular, its bottom-right skip hand
        // otherwise sits directly on top of the map's PLAY label.
        q3.b.j0.O.f = false;
        q3.b.j0.P.f = false;
        q3.b.j0.f0.w.f = false;
        if (cutscene.h()) {
            return;
        }
        cutscene.addListener(onCutsceneEnded);
        cutscene.start(script, SUPPRESS_NOTHING);
        q3.b.j0.N.x(cutscene);
        cutscene.t();
    }

    @Override
    public void C() {
        map.C();
    }

    @Override
    public void q() {
        map.q();
    }

    @Override
    public void h() {
        map.h();
    }

    @Override
    public void t(int x, int y) {
        q3.b.j0.N.p(x, y);
    }

    @Override
    public void s(int x, int y) {
        if (q3.b.j0.N.A()) {
            q3.b.j0.N.o(x, y);
        }
    }

    @Override
    public void u(int x, int y) {
        if (q3.b.j0.N.A()) {
            q3.b.j0.N.q(x, y);
        }
    }

    /** Back skips the intro, the same as the widget's own corner. */
    @Override
    public void j() {
        leave();
    }

    /**
     * Hand the intro's place in the stack to the Academy map and drop out of it.
     *
     * <p>Not {@code mojo.b.i.d(map)}, which is what raises a screen on top of
     * another: that sets the new screen's parent to this one, so Back out of
     * the map would land on the intro and play it again. The intro is a beat,
     * not a place you can return to, so the map inherits the parent this
     * activity was raised from - the title menu - and this activity is simply
     * gone.</p>
     */
    private void leave() {
        if (left) {
            return;
        }
        left = true;
        if (cutscene.h()) {
            cutscene.close();
        }
        map.d = this.d;
        mojo.b.i.g(DuelNameActivity.before(map));
    }

    /** The widget, so a check can drive and inspect the real thing. */
    AcademyCutsceneWidget widget() {
        return cutscene;
    }
}
