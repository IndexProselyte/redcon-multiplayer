package redcon.desktop;

import mojo.Activity;

/**
 * The pre-war Academy presentation of REDCON's shipped campaign world map.
 *
 * <p>This activity deliberately wraps, rather than modifies, {@link q3.b}.
 * The campaign owns the map data, widgets and renderer; multiplayer borrows
 * those objects while this activity is visible and restores every field it
 * changes before returning to the title menu. Input is sent directly to the
 * map widget, never to the campaign activity, so the campaign's event-1 battle
 * action and {@code q3.b.K()} cannot be reached from this route.</p>
 */
public final class AcademyMapActivity extends Activity {

    static final String ACADEMY_NAME = "Krux Military Academy";

    private final q3.b campaign = q3.b.j0;
    private Snapshot snapshot;
    private q3.e academy;
    private q3.e pressedMarker;
    private i3.q backButton;
    private boolean backPressed;
    private boolean playPressed;
    private boolean leaving;

    @Override
    public void B() {
        if (campaign == null || campaign.N == null || campaign.f0 == null) {
            throw new IllegalStateException("campaign map is not initialized");
        }

        // q3.b.B() also resumes campaign cutscenes and progress work. The map
        // widget needs only its own one-time texture/camera initialization.
        campaign.f0.B();
        campaign.h();

        if (snapshot == null) {
            snapshot = new Snapshot(campaign);
            academy = findAcademy(campaign.f0.v);
            if (academy == null) {
                throw new IllegalStateException(
                    "campaign map has no marker for " + ACADEMY_NAME);
            }
        }

        presentAcademyMap();
        // q3.b.O is a full-canvas event container; its only child is the
        // visible hamburger glyph. Use the glyph bounds or every map tap would
        // look like Back merely because it lies inside the parent container.
        backButton = campaign.O != null && campaign.O.t > 0
            ? campaign.O.u[0] : null;
    }

    @Override
    public void C() {
        ensureAcademySelection();
        // Update the shipped widget tree but not q3.b.C(), whose campaign-only
        // tail can save progress and resume store/cutscene state.
        campaign.N.w();
    }

    @Override
    public void q() {
        campaign.N.n();
    }

    @Override
    public void h() {
        if (campaign != null && campaign.N != null) {
            campaign.h();
        }
    }

    @Override
    public void t(int x, int y) {
        playPressed = false;
        backPressed = backButton != null && backButton.d(x, y);
        if (backPressed) {
            pressedMarker = null;
            return;
        }
        playPressed = campaign.P != null && campaign.P.d(x, y);
        if (playPressed) {
            pressedMarker = null;
            return;
        }
        pressedMarker = campaign.f0.y(x, y);
        campaign.f0.p(x, y);
    }

    @Override
    public void s(int x, int y) {
        if (!backPressed && !playPressed) {
            campaign.f0.o(x, y);
        }
    }

    @Override
    public void u(int x, int y) {
        if (backPressed) {
            boolean activate = backButton != null && backButton.d(x, y);
            backPressed = false;
            if (activate) {
                j();
            }
            return;
        }
        if (playPressed) {
            boolean activate = campaign.P != null && campaign.P.d(x, y);
            playPressed = false;
            if (activate) {
                openPairing();
            }
            return;
        }

        q3.c map = campaign.f0;
        boolean dragged = map.H;
        q3.e releasedMarker = map.y(x, y);

        // Let the shipped map finish a pan and calculate inertia. A marker
        // click is completed here instead, because q3.c.q() would select every
        // reachable campaign marker.
        if (dragged || pressedMarker == null) {
            map.q(x, y);
        } else {
            map.H = false;
            map.Q = null;
        }

        boolean startDuel = !dragged
            && pressedMarker == academy
            && releasedMarker == academy;
        pressedMarker = null;
        if (startDuel) {
            openPairing();
        }
    }

    @Override
    public void pointerHover(int x, int y) {
        // Inert campaign locations do not react as though they can be chosen.
    }

    @Override
    public void j() {
        if (leaving) {
            return;
        }
        leaving = true;
        restoreCampaignMap();
        GameContext.enterCampaign();
        super.j();
    }

    /** Marker selected by the Academy route, exposed to the product gate. */
    q3.e academyMarker() {
        return academy;
    }

    /** The borrowed native map widget, exposed to layout and input gates. */
    q3.c map() {
        return campaign.f0;
    }

    i3.q backControl() {
        return backButton;
    }

    /** Restore immediately in a check without navigating the activity stack. */
    void restoreForCheck() {
        restoreCampaignMap();
    }

    /** Reapply the borrowed map state before Pairing returns to this activity. */
    void prepareForReturn() {
        if (!leaving && snapshot != null && academy != null) {
            presentAcademyMap();
        }
    }

    private void presentAcademyMap() {
        refreshAcademyReference();
        for (q3.e marker : campaign.f0.v) {
            marker.h = true;
            marker.i = false;
            // q3.e.j selects the alternate Krux set. The Empire campaign set
            // is the false branch ({447, 442, 446, 444}).
            marker.j = false;
            marker.m = marker == academy;
            marker.h();
        }

        q3.c map = campaign.f0;
        academy.g = DuelStrings.of(DuelStrings.ACADEMY_DRILL);
        map.S = academy;
        map.R = null;
        map.Q = null;
        map.V = Integer.MAX_VALUE;
        campaign.M = academy.e;
        campaign.O.f = true;
        campaign.P.f = true;
        campaign.Q.f = false;
        campaign.W.f = false;
        map.w.t = null;
        map.F();
        campaign.P.B = campaign.T;
        i3.h.f(campaign.N, campaign.P);
        map.z(academy);
    }

    /**
     * Battle setup borrows the campaign singleton and can clear its selection.
     * Keep the Academy selected while this wrapper owns the visible map.
     */
    private void ensureAcademySelection() {
        if (leaving) {
            return;
        }
        refreshAcademyReference();
        boolean soleSelection = campaign.f0.S == academy;
        for (q3.e marker : campaign.f0.v) {
            if (marker.m != (marker == academy)) {
                soleSelection = false;
                break;
            }
        }
        if (soleSelection) {
            return;
        }
        for (q3.e marker : campaign.f0.v) {
            marker.m = marker == academy;
            marker.h();
        }
        campaign.f0.S = academy;
        campaign.M = academy.e;
        campaign.f0.F();
    }

    /** The original fort builder reconstructs q3.b's shared marker array. */
    private void refreshAcademyReference() {
        q3.e current = findAcademy(campaign.f0.v);
        if (current == null) {
            throw new IllegalStateException(
                "campaign map has no marker for " + ACADEMY_NAME);
        }
        academy = current;
    }

    private void openPairing() {
        if (mojo.b.i.b == this) {
            // The Academy is the visual entry into multiplayer, but peers
            // still need a place to host or join before Pairing can exist.
            // Skipping Browse here silently created a local stand-in duel on
            // every device and made the real LAN path unreachable.
            mojo.b.i.d(new DuelBrowseActivity());
        }
    }

    private void restoreCampaignMap() {
        if (snapshot == null) {
            return;
        }
        snapshot.restore(campaign);
        snapshot = null;
        academy = null;
        pressedMarker = null;
    }

    private static q3.e findAcademy(q3.e[] markers) {
        if (markers == null) {
            return null;
        }
        Object authoredAcademy = mojo.f.c.length > 153 ? mojo.f.c[153] : null;
        for (q3.e marker : markers) {
            if (marker == null || marker.e == null || marker.e.i == null) {
                continue;
            }
            if (marker.e.i == authoredAcademy
                    || ACADEMY_NAME.equals(marker.e.i.d)) {
                return marker;
            }
        }
        return null;
    }

    /** Only the presentation fields touched by this activity. */
    private static final class Snapshot {
        private final q3.e[] markers;
        private final boolean[] reached;
        private final boolean[] completed;
        private final boolean[] empire;
        private final boolean[] selected;
        private final String[] subtitles;
        private final q3.e mapSelected;
        private final q3.e mapHover;
        private final q3.e mapPressed;
        private final int hoverDeadline;
        private final q3.v mission;
        private final float cameraX;
        private final float cameraY;
        private final boolean xpVisible;
        private final boolean missionsVisible;
        private final boolean backVisible;
        private final boolean playVisible;
        private final Object playLabel;

        Snapshot(q3.b campaign) {
            q3.c map = campaign.f0;
            markers = map.v;
            reached = new boolean[markers.length];
            completed = new boolean[markers.length];
            empire = new boolean[markers.length];
            selected = new boolean[markers.length];
            subtitles = new String[markers.length];
            for (int i = 0; i < markers.length; i++) {
                reached[i] = markers[i].h;
                completed[i] = markers[i].i;
                empire[i] = markers[i].j;
                selected[i] = markers[i].m;
                subtitles[i] = markers[i].g;
            }
            mapSelected = map.S;
            mapHover = map.R;
            mapPressed = map.Q;
            hoverDeadline = map.V;
            mission = campaign.M;
            cameraX = map.D;
            cameraY = map.E;
            xpVisible = campaign.Q.f;
            missionsVisible = campaign.W.f;
            backVisible = campaign.O.f;
            playVisible = campaign.P.f;
            playLabel = campaign.P.B;
        }

        void restore(q3.b campaign) {
            q3.c map = campaign.f0;
            // q3.b.H(), used to reopen the original fort builder, replaces
            // this array. Put back the exact campaign marker objects whose
            // fields were snapshotted before Multiplayer borrowed the map.
            map.v = markers;
            for (int i = 0; i < markers.length; i++) {
                markers[i].h = reached[i];
                markers[i].i = completed[i];
                markers[i].j = empire[i];
                markers[i].m = selected[i];
                markers[i].g = subtitles[i];
                markers[i].h();
            }
            map.S = mapSelected;
            map.R = mapHover;
            map.Q = mapPressed;
            map.V = hoverDeadline;
            map.D = cameraX;
            map.E = cameraY;
            campaign.M = mission;
            campaign.Q.f = xpVisible;
            campaign.W.f = missionsVisible;
            campaign.O.f = backVisible;
            campaign.P.f = playVisible;
            campaign.P.B = playLabel;
            map.F();
            i3.h.f(campaign.N, campaign.P);
        }
    }
}
