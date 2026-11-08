package redcon.desktop;

import redcon.net.DuelEvent;
import redcon.net.DuelRuntime;
import redcon.net.NetworkDuelRuntime;

/**
 * The lobby for one duel: who is playing and under what rules.
 *
 * <p>Same sub-screen grammar as {@link DuelBrowseActivity}. On the network
 * route the commander rows are the two paired handshakes and the rule lines
 * are the committed revision both clients agreed on: either player may edit
 * them, the host orders competing edits, and both screens redraw from the same
 * committed values. On the single-device development route the rules are this
 * screen's own local choices, exactly as before.</p>
 */
public final class DuelPairingActivity extends f3.e {

    public static final int EVENT_BUILD = 94;
    public static final int EVENT_CANCEL = 95;
    public static final int EVENT_BUDGET = 96;
    public static final int EVENT_BUILD_TIME = 97;
    public static final int EVENT_MATCH_LIMIT = 98;
    public static final int EVENT_BUDGET_PREVIOUS = 103;
    public static final int EVENT_BUILD_TIME_PREVIOUS = 104;
    public static final int EVENT_MATCH_LIMIT_PREVIOUS = 105;

    private static final int[] BUDGETS = {
        2000, 4000, 6000, 8000, 10000,
        12000, 14000, 16000, 18000, 20000,
        22000, 24000, 26000, 28000, 30000,
        32000, 34000, 36000, 38000, 40000
    };
    private static final int[] BUILD_TIMES = {30, 60, 90, 120, 180};
    private static final int[] MATCH_LIMITS = {300, 600, 900, 1200};

    private int budgetIndex = 0;
    private int buildTimeIndex = 1;
    private int matchLimitIndex = 1;
    private i3.n budgetValue;
    private i3.n buildTimeValue;
    private i3.n matchLimitValue;
    private long shownRevision;

    @Override
    public void a0() {
        k = true;
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        if (runtime != null) {
            DuelNetwork.bindPairing(this);
            adoptCommittedRules(runtime.rules());
        }
        super.a0();
    }

    @Override
    public void G(i3.j root) {
        H(root);
        // The confirm action goes where REDCON puts one on a screen it owns:
        // the bottom-right corner, built by the shell's own N(...).
        N(root, EVENT_BUILD, startLabel(), null);
    }

    @Override
    public void I(i3.j root, i3.b list) {
        DuelMenu.column(list);

        DuelMenu.heading(list, DuelStrings.of(DuelStrings.COMMANDERS));
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        String ready = DuelStrings.of(DuelStrings.READY);
        if (runtime == null) {
            DuelMenu.row(list, this, 0,
                DuelStrings.of(DuelStrings.PLAYER_ONE), ready, -1);
            DuelMenu.row(list, this, 0,
                DuelStrings.of(DuelStrings.STAND_IN), ready, -1);
        } else {
            // Near is always the host. Both rows are paired commanders by the
            // time this screen exists, so both read as present.
            DuelMenu.row(list, this, 0, DuelNetwork.nearName(), ready, -1);
            DuelMenu.row(list, this, 0, DuelNetwork.farName(), ready, -1);
        }

        DuelMenu.heading(list, DuelStrings.of(DuelStrings.RULES));
        budgetValue = DuelMenu.choice(
            list, this, EVENT_BUDGET_PREVIOUS, EVENT_BUDGET,
            DuelStrings.of(DuelStrings.BUDGET),
            budgetText());
        buildTimeValue = DuelMenu.choice(
            list, this, EVENT_BUILD_TIME_PREVIOUS, EVENT_BUILD_TIME,
            DuelStrings.of(DuelStrings.BUILD_TIME),
            buildTimeText());
        matchLimitValue = DuelMenu.choice(
            list, this, EVENT_MATCH_LIMIT_PREVIOUS, EVENT_MATCH_LIMIT,
            DuelStrings.of(DuelStrings.MATCH_LIMIT),
            matchLimitText());
    }

    @Override
    public void X(int event, Object data) {
        if (event == EVENT_BUILD) {
            beginBuild();
        } else if (event == EVENT_BUDGET) {
            budgetIndex = next(budgetIndex, BUDGETS);
            budgetValue.y(budgetText());
            proposeRules();
        } else if (event == EVENT_BUDGET_PREVIOUS) {
            budgetIndex = previous(budgetIndex, BUDGETS);
            budgetValue.y(budgetText());
            proposeRules();
        } else if (event == EVENT_BUILD_TIME) {
            buildTimeIndex = next(buildTimeIndex, BUILD_TIMES);
            buildTimeValue.y(buildTimeText());
            proposeRules();
        } else if (event == EVENT_BUILD_TIME_PREVIOUS) {
            buildTimeIndex = previous(buildTimeIndex, BUILD_TIMES);
            buildTimeValue.y(buildTimeText());
            proposeRules();
        } else if (event == EVENT_MATCH_LIMIT) {
            matchLimitIndex = next(matchLimitIndex, MATCH_LIMITS);
            matchLimitValue.y(matchLimitText());
            proposeRules();
        } else if (event == EVENT_MATCH_LIMIT_PREVIOUS) {
            matchLimitIndex = previous(matchLimitIndex, MATCH_LIMITS);
            matchLimitValue.y(matchLimitText());
            proposeRules();
        } else if (event == EVENT_CANCEL || event == 1) {
            j();
        }
    }

    /**
     * Follow the paired match from the frame pump.
     *
     * <p>Only the peer's rule edits and a lost link can change this screen
     * without the player touching it, and both come from the runtime rather
     * than from a widget.</p>
     */
    static void refreshActive() {
        if (!(mojo.b.i.b instanceof DuelPairingActivity)) {
            return;
        }
        DuelPairingActivity screen = (DuelPairingActivity) mojo.b.i.b;
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        if (runtime == null) {
            if (!DuelNetwork.failure().isEmpty()) {
                // The peer is gone. Back to Browse, which says why.
                screen.j();
            }
            return;
        }
        DuelEvent.RulesSet rules = runtime.rules();
        if (rules != null && rules.revision != screen.shownRevision) {
            screen.adoptCommittedRules(rules);
            screen.a0();
            screen.h();
        }
    }

    @Override
    public void B() {
        DuelBuildFlow.closeIfReturningTo(this);
        super.B();
    }

    @Override
    public void j() {
        if (DuelNetwork.runtime() != null) {
            DuelNetwork.reset();
        }
        if (d instanceof AcademyMapActivity) {
            ((AcademyMapActivity) d).prepareForReturn();
        }
        super.j();
    }

    int creditBudget() {
        return BUDGETS[budgetIndex];
    }

    static int[] budgetChoices() {
        return BUDGETS.clone();
    }

    int buildSeconds() {
        return BUILD_TIMES[buildTimeIndex];
    }

    int matchSeconds() {
        return MATCH_LIMITS[matchLimitIndex];
    }

    // ----------------------------------------------------------------- inner

    private void beginBuild() {
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        if (runtime == null) {
            DuelBuildFlow.start(this);
            return;
        }
        System.err.println("duel begin build host=" + runtime.isHost()
                           + " started=" + runtime.started()
                           + " rules=" + (runtime.rules() == null ? "none"
                               : "r" + runtime.rules().revision));
        if (!runtime.isHost() || runtime.started()) {
            // The guest's screen shows what it is waiting for rather than a
            // control that would do nothing.
            return;
        }
        if (runtime.rules() == null) {
            // Nobody has edited anything yet; commit this screen's defaults so
            // there is a revision to build under.
            runtime.proposeRules(creditBudget(), buildSeconds(), matchSeconds());
            return;
        }
        runtime.beginBuild();
    }

    /** Offer this screen's rules; the host orders them into a revision. */
    private void proposeRules() {
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        if (runtime == null || runtime.started()
                || runtime.link() != DuelRuntime.Link.LIVE) {
            return;
        }
        runtime.proposeRules(creditBudget(), buildSeconds(), matchSeconds());
    }

    /** Move the three selectors onto the committed values both clients hold. */
    private void adoptCommittedRules(DuelEvent.RulesSet rules) {
        if (rules == null) {
            return;
        }
        shownRevision = rules.revision;
        budgetIndex = indexOf(BUDGETS, rules.creditBudget, budgetIndex);
        buildTimeIndex = indexOf(BUILD_TIMES, rules.buildSeconds, buildTimeIndex);
        matchLimitIndex =
            indexOf(MATCH_LIMITS, rules.matchSeconds, matchLimitIndex);
    }

    private String startLabel() {
        NetworkDuelRuntime runtime = DuelNetwork.runtime();
        if (runtime == null || runtime.isHost()) {
            return DuelStrings.of(DuelStrings.BEGIN_BUILD);
        }
        return DuelStrings.of(DuelStrings.WAITING_FOR_OPPONENT);
    }

    private String budgetText() {
        return "$" + creditBudget();
    }

    private String buildTimeText() {
        return DuelMenu.clock(buildSeconds());
    }

    private String matchLimitText() {
        return DuelMenu.clock(matchSeconds());
    }

    private static int indexOf(int[] choices, int value, int fallback) {
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == value) {
                return index;
            }
        }
        return fallback;
    }

    private static int next(int index, int[] choices) {
        return (index + 1) % choices.length;
    }

    private static int previous(int index, int[] choices) {
        return (index + choices.length - 1) % choices.length;
    }
}
