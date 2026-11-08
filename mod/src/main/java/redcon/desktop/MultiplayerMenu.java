package redcon.desktop;

import mojo.EventListener;

/** Adds one native row to the recovered title menu without replacing it. */
public final class MultiplayerMenu {

    public static final int EVENT_MULTIPLAYER = 91;
    private static p3.i installedOn;
    private static i3.e row;

    private static final EventListener OPEN = new EventListener() {
        @Override
        public void handleEvent(int event, Object source, Object data) {
            if (event != EVENT_MULTIPLAYER) {
                return;
            }
            GameContext.enterDuel();
            // First entry on an install opens with the Academy intro; every
            // entry after it goes straight to the Academy map.
            DuelCutsceneActivity.openMultiplayer();
        }
    };

    private MultiplayerMenu() {
    }

    /** Install after p3.i has completed B(), when its native list exists. */
    public static synchronized void install(p3.i menu) {
        if (menu == null || menu == installedOn) {
            return;
        }
        // A development-signed reconstruction cannot authenticate against
        // REDCON's Google Play Games identity. The stock foreground callback
        // otherwise opens Android's account-setup wizard over every test run.
        // Campaign play and local saves are independent of that cloud service.
        menu.s = false;
        if (menu.l == null) {
            throw new IllegalStateException("title menu list has not been built");
        }

        row = menu.L(menu.l, OPEN, EVENT_MULTIPLAYER, DuelStrings.of(DuelStrings.MULTIPLAYER));

        // L appends. Keep START first, then place the additive route beside it;
        // every existing REDCON row retains its original relative order.
        i3.b list = menu.l;
        int appended = list.t - 1;
        if (appended > 1) {
            System.arraycopy(list.u, 1, list.u, 2, appended - 1);
            list.u[1] = row;
        }
        menu.F(list);
        // Activity.configurationChanged only updates platform feature flags;
        // h() is the stock full-canvas layout pass that assigns child bounds.
        menu.h();
        installedOn = menu;
    }

    static i3.e row() {
        return row;
    }
}
