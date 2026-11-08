package redcon.desktop;

/**
 * General Kranz introducing the Academy drill, as a REDCON cutscene script.
 *
 * <p>The format is the game's own. A script is a {@code j3.b} holding
 * {@code q3.w} lines; {@code q3.j} plays it. Each line carries a condition mask,
 * a speaker index, a portrait offset and its text. Nothing here is a new kind of
 * object — {@code q3.v.a} builds exactly this shape when it reads a campaign
 * mission's dialogue out of the content database.</p>
 *
 * <h2>The text format, which is easy to get wrong</h2>
 *
 * <p>Colour is switched inline by a form feed followed by a digit, and the
 * digit indexes {@code m3.a.d}, the palette {@code q3.j} installs:
 * {@code 0} grey body, {@code 1} white, {@code 2} dark red, {@code 3} green,
 * {@code 4} cyan, {@code 5} amber. So {@code "\f1word\f0"} renders "word" in
 * white. {@code c3.c.q} skips the pair when it measures a line and
 * {@code c3.b} consumes it when it builds the glyphs, so a code costs no
 * width. A digit on its own is just a digit — an earlier reading of this
 * format had the escape as the bare digit, which is why the first Academy
 * script's codes were painted as literal characters.</p>
 *
 * <p>{@code \n} is a line break. His shipped lines average 5.8 words between
 * breaks and never exceed thirteen, so these break on the rhetorical beat
 * rather than at a width limit, and carry at most one emphasis span each. Full
 * rules, and the corpus they were measured from, are in
 * {@code docs/kranz-voice.md}.</p>
 *
 * <h2>Why the portrait offsets are copied and not chosen</h2>
 *
 * <p>{@code q3.w.d} and {@code q3.w.e} frame the speaker's portrait, and the
 * shipped values are large and unexplained. Rather than guess them, this reads
 * them off Kranz's own lines at the Krux Military Academy mission, so he is
 * framed here exactly as he is framed there.</p>
 */
final class KranzIntro {

    /** A native cutscene plate with no portrait. */
    private static final int NARRATOR = AcademyCutsceneWidget.NARRATOR;

    /** Kranz. The same speaker index as all 168 of his campaign lines. */
    private static final int KRANZ = 2;

    /** Fuhrer Adler Grimm. */
    private static final int ADLER = 3;

    /** Shown whatever the platform: 1 is touch-only, 2 desktop-only, 0 both. */
    private static final short ANY_PLATFORM = 0;

    /**
     * The drill, in the lecturer register.
     *
     * <p>He is not the Traitor General here. In the Academy years he is Master
     * General under Grimm, and the grievance that drives every campaign line has
     * not happened yet, so there is no bitterness and no threat in this. He
     * grades. That is the whole character in this scene.</p>
     */
    private static final int[] SPEAKERS = {
        NARRATOR, NARRATOR, NARRATOR,
        KRANZ, KRANZ, KRANZ, KRANZ,
        NARRATOR, NARRATOR,
        ADLER
    };

    private static final String[] LINES = {
        "It is thirty years before\nthe Great Unification War started.",

        "Adler is still constructing his war machine.\nGeneral Kranz, by his side.",

        "And our future Strike Commander Triumphant\nhas just completed the first year\nat the Military Academy.",

        "Ah.\nThere you are, cadet.",

        "No theory today.\nYou will engage in a \f1fort duel\f0\nwith other students.",

        "The Empire has a surplus of cadets\nand old forts that are finally\ngoing to be put to use.",

        "Try not to die,\nand show me what you \f1learned\f0.",

        "...",

        "...",

        // Grimm's answer to a general, in the shipped construction: a blank
        // line, the green stage-direction colour, the sound between asterisks.
        // Mission 56 has the original - "\n\f3* Prrrrrrt *\n" - and this is
        // the same line with the Academy's own noise in it.
        "\n\f3* Pffft *\n"
    };

    private KranzIntro() {
    }

    /**
     * Build the script. Returns null if the recovered line class cannot be
     * allocated, which is the only way this can fail.
     *
     * <p>{@code q3.w} carries no constructor of its own in the enjarified jar:
     * dex lets a class call its superclass constructor on a fresh instance and
     * the JVM does not, which is the same reason the harnesses run with
     * verification off. {@code RecoveredGame.allocate} is how the rest of the
     * project makes one of these, so it is how this does too.</p>
     */
    static j3.b script() {
        int[] framing = portraitFraming(KRANZ, true);
        int[] adlerFraming = portraitFraming(ADLER, false);
        j3.b built = new j3.b();
        built.a = framing[0];
        built.b = framing[1];
        q3.w[] lines = new q3.w[LINES.length];
        int speakers = 0;
        for (int i = 0; i < LINES.length; i++) {
            q3.w line;
            try {
                line = RecoveredGame.allocate(q3.w.class);
            } catch (Exception unavailable) {
                return null;
            }
            line.b = ANY_PLATFORM;
            int speaker = SPEAKERS[i];
            line.c = (short) speaker;
            int[] lineFraming = speaker == ADLER ? adlerFraming : framing;
            line.d = speaker == NARRATOR ? 0 : lineFraming[0];
            line.e = speaker == NARRATOR ? 0 : lineFraming[1];
            line.f = LINES[i];
            lines[i] = line;
            if (speaker >= 0) {
                speakers |= 1 << speaker;
            }
        }
        built.c = speakers;
        built.d = lines;
        return built;
    }

    /**
     * Kranz's own portrait framing, read off his shipped lines.
     *
     * <p>Prefers the Krux Military Academy mission, which is where he speaks as
     * the player's teacher; falls back to any line of his, and to zero if the
     * campaign's dialogue is not loaded, which {@code q3.j} treats as unframed.
     */
    private static int[] portraitFraming(int speaker, boolean preferAcademy) {
        int[] fallback = {0, 0};
        q3.b campaign = null;
        for (mojo.f datum : mojo.f.c) {
            if (datum instanceof q3.b) {
                campaign = (q3.b) datum;
                break;
            }
        }
        if (campaign == null || campaign.l == null) {
            return fallback;
        }
        for (q3.v mission : campaign.l) {
            if (mission == null) {
                continue;
            }
            boolean academy = mission.i != null
                && "Krux Military Academy".equals(mission.i.d);
            for (j3.b script : new j3.b[] {mission.q, mission.r, mission.s, mission.t}) {
                if (script == null || !(script.d instanceof q3.w[])) {
                    continue;
                }
                for (q3.w line : (q3.w[]) script.d) {
                    if (line == null || line.c != speaker) {
                        continue;
                    }
                    if (preferAcademy && academy) {
                        return new int[] {line.d, line.e};
                    }
                    if (fallback[0] == 0 && fallback[1] == 0) {
                        fallback = new int[] {line.d, line.e};
                    }
                }
            }
        }
        return fallback;
    }
}
