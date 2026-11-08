package redcon.desktop;

import o3.z0;
import redcon.duel.FortDesign;

/**
 * A duel-safe doorway into REDCON's shipped campaign fortress customizer.
 *
 * <p>The adapter owns no visual style. Its activity is {@code o3.b}, the
 * original CUSTOMIZE screen initialized by the game's own boot pump. The only
 * desktop/duel work here is supplying the scene record a campaign normally
 * supplies and keeping each player's mutable blueprint independent.
 */
public final class OriginalFortBuilder {

    private final q3.b campaign;
    private final o3.b builder;

    private FortDesign opened;
    private mojo.Activity returnActivity;

    /** Initialize the original screen and its inventory/widget model. */
    public OriginalFortBuilder() {
        campaign = q3.b.j0;
        if (campaign == null || campaign.e0 == null) {
            throw new IllegalStateException("the game UI has not been booted");
        }
        campaign.H();
        builder = campaign.e0;
    }

    /**
     * Open an independent design in the original editor and make it current.
     * Call only after a real/headless render setup has completed the boot pump.
     */
    public void open(FortDesign design) throws Exception {
        if (design == null) {
            throw new IllegalArgumentException("design is null");
        }
        opened = design.copy();
        z0 editable = design.instantiate();
        builder.J0(editable, false);

        // B() builds the scenery camera. In campaign play q3.b has already
        // populated this record; a standalone duel UI has no mission, so give
        // it a valid all-zero config and let REDCON choose its normal random
        // backdrop fields through e1.a.
        q3.v scene = RecoveredGame.allocate(q3.v.class);
        scene.f = editable;
        scene.i = editable;
        campaign.d0.a = scene;
        campaign.d0.a(scene);
        campaign.M = scene;

        builder.d = returnActivity == null ? campaign : returnActivity;
        builder.B();
        mojo.b.i.b = builder;
    }

    /** Save the editor's live fortress back into a new immutable snapshot. */
    public FortDesign capture() {
        if (opened == null || builder.g2 == null) {
            throw new IllegalStateException("no design is open");
        }
        builder.g2.h(builder.n1);
        return opened.captureEdited(builder.g2);
    }

    /**
     * Detect a chassis chosen through REDCON's own selector. Such a click loads
     * the shared content-table object; the controller must immediately reopen
     * an independent copy before the player can edit it.
     */
    public FortDesign contentSelection() {
        if (builder.g2 == null || mojo.f.c == null) {
            return null;
        }
        for (mojo.f content : mojo.f.c) {
            if (content == builder.g2) {
                return FortDesign.capture(builder.g2);
            }
        }
        return null;
    }

    public o3.b activity() {
        return builder;
    }

    /** The same structural gate used by the stock BATTLE event. */
    public boolean readyLegal() {
        o3.s0 fort = builder.n1;
        if (fort == null || fort.Q == 0) {
            return false;
        }
        int condition = fort.O;
        boolean mode = fort.O(5);
        return !((mode && condition == 0) || (!mode && condition > 0));
    }

    /** Set the native activity restored by REDCON's own Back behavior. */
    public void returnTo(mojo.Activity activity) {
        returnActivity = activity;
    }

    public String title() {
        return opened == null ? "CUSTOMIZE" : opened.name();
    }
}
