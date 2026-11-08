package redcon.desktop;

/** Name entry completes on the game thread before a lobby can advertise it. */
public final class DuelNameActivity extends f3.e {
    private final mojo.Activity destination;
    private final java.util.function.BiConsumer<DuelNameActivity, String> input;
    private volatile String answer;
    private volatile boolean answered;
    private boolean requesting;
    private String error = "";

    DuelNameActivity(mojo.Activity destination) { this(destination, DuelNameActivity::platformInput); }
    DuelNameActivity(mojo.Activity destination, java.util.function.BiConsumer<DuelNameActivity, String> input) {
        this.destination = destination; this.input = input; k = true;
    }

    static mojo.Activity before(mojo.Activity destination) {
        if (DuelPlayerProfile.name() != null) return destination;
        DuelNameActivity prompt = new DuelNameActivity(destination);
        prompt.d = destination.d;
        return prompt;
    }

    @Override public void B() { super.B(); request(); }
    @Override public void I(i3.j root, i3.b list) {
        DuelMenu.column(list);
        DuelMenu.heading(list, "COMMANDER NAME");
        DuelMenu.note(list, "Choose the name other players will see.");
        DuelMenu.row(list, this, 2, "CHOOSE NAME", null, -1);
        if (!error.isEmpty()) DuelMenu.note(list, error);
    }
    @Override public void G(i3.j root) { H(root); }
    @Override public void X(int event, Object data) { if (event == 2) request(); else if (event == 1) j(); }

    private void request() {
        if (requesting) return;
        requesting = true;
        try {
            input.accept(this, DuelIdentity.playerName());
        } catch (Exception failure) {
            requesting = false;
            error = "Could not open name entry. Try again.";
            DuelAppLog.error("Name entry", failure);
            a0(); h();
        }
    }
    private static void platformInput(DuelNameActivity receiver, String initial) {
        try {
            String platform = mojo.Platform.C.getClass().getName().equals("redcon.desktop.DesktopMainActivity")
                ? "DesktopDuelNameInput" : "AndroidDuelNameInput";
            Class.forName("redcon.desktop." + platform).getMethod("show", DuelNameActivity.class, String.class)
                .invoke(null, receiver, initial);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public void answer(String value) { answer = value; answered = true; }

    @Override public void C() {
        super.C();
        if (!answered) return;
        answered = false; requesting = false;
        if (answer == null) return;
        try {
            DuelPlayerProfile.save(answer);
            destination.d = d;
            mojo.b.i.g(destination);
        } catch (Exception failure) {
            error = failure instanceof IllegalArgumentException ? failure.getMessage() : "Could not save name. Try again.";
            DuelAppLog.error("Save multiplayer name", failure);
            a0(); h();
        }
    }
    @Override public void j() {
        if (requesting) return;
        if (DuelPlayerProfile.name() != null) { destination.d = d; mojo.b.i.g(destination); }
        else { GameContext.enterCampaign(); super.j(); }
    }
}
