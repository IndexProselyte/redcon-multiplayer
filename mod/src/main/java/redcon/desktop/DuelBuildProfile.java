package redcon.desktop;

import o3.j1;
import o3.p1;
import redcon.duel.DuelBuildCatalog;
import redcon.duel.FortDesign;

/**
 * A reversible duel inventory layered over REDCON's campaign customizer.
 *
 * <p>The original UI reads progression, owned items, and credits from encrypted
 * global save slots. Duel mode temporarily grants every authored build item,
 * keeps premium/free-build disabled, and uses the same credit slot for a
 * configurable per-player purse. Closing this object restores every slot it
 * changed, so opening a duel cannot advance or damage a campaign save.
 */
public final class DuelBuildProfile implements AutoCloseable {
    private static DuelBuildProfile active;
    private final boolean savedStatsVisible;
    private final boolean savedStatsEnabled;

    // Bits 0-11 are the persistent entitlements: p1.j() keeps flags & 4095
    // when it resets a profile and drops the rest. Bit 12 is the per-run
    // customizer flag q3.b.T() sets, and leaving it clear is the state the
    // base game's own shop rows run in - so this grants everything a campaign
    // could have unlocked without pretending the duel is mid-campaign.
    private static final int ALL_PERSISTENT_FLAGS = 0x0fff;
    private static final int MAX_LEVEL = 60;
    /** Campaign bookkeeping only; duel row availability ignores this stock count. */
    private static final int MAX_OWNED = 30;

    private final o3.b builder;
    private final p1 profile;
    private final DuelBuildCatalog catalog;
    private final int budget;
    private final int savedFlags;
    private final int savedCredits;
    private final int savedLevel;
    private final int[] savedInventory;

    private boolean closed;
    private DuelBuildCatalog.Cost currentCost;

    public DuelBuildProfile(o3.b builder, DuelBuildCatalog catalog, int budget) {
        if (builder == null || builder.C1 == null || builder.D1 == null) {
            throw new IllegalArgumentException("the original builder is not initialized");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog is null");
        }
        if (budget < 0) {
            throw new IllegalArgumentException("budget must be non-negative");
        }
        this.builder = builder;
        this.profile = builder.C1;
        this.catalog = catalog;
        this.budget = budget;
        savedStatsVisible = builder.z1.f;
        savedStatsEnabled = (builder.z1.d & 1) != 0;
        savedFlags = c1.a.s(profile.d);
        savedCredits = c1.a.s(profile.f);
        savedLevel = c1.a.s(profile.h);
        savedInventory = saveInventory();

        // The exact original flags return in close().
        c1.a.R(profile.d, ALL_PERSISTENT_FLAGS);
        c1.a.R(profile.h, MAX_LEVEL);
        for (j1 item : builder.D1.g) {
            if (item != null) {
                c1.a.S(profile.m, 6, item.f, MAX_OWNED);
            }
        }
        c1.a.R(profile.f, budget);
        active = this;
        refreshInventory();
    }

    /** Rebase the original credit display on an independently loaded design. */
    public DuelBuildCatalog.Cost prepare(FortDesign design) {
        requireOpen();
        currentCost = catalog.cost(design);
        c1.a.R(profile.f, Math.max(0, currentCost.remaining(budget)));
        builder.z1.B();
        builder.f1(false);
        return currentCost;
    }

    public int budget() {
        return budget;
    }

    public int remainingCredits() {
        requireOpen();
        refreshBalance();
        return c1.a.s(profile.f);
    }

    static boolean owns(o3.b editor) {
        return active != null && !active.closed && active.builder == editor;
    }

    static DuelBuildProfile activeProfile() { return active; }

    void refreshBalance() {
        if (builder.g2 != null && builder.n1 != null) {
            o3.z0 copy = FortDesign.capture(builder.g2).instantiate();
            copy.h(builder.n1);
            currentCost = catalog.cost(FortDesign.capture(copy));
            c1.a.R(profile.f, Math.max(0, currentCost.remaining(budget)));
        }
        builder.z1.f = false;
        builder.z1.v(false);
    }

    static void refreshEditor(o3.b editor) {
        if (!owns(editor)) return;
        active.refreshBalance();
        o3.b.d1(editor.r1, true);
        o3.b.d1(editor.t1, true);
        o3.b.d1(editor.s1, true);
    }

    public boolean currentDesignOverBudget() {
        requireOpen();
        return currentCost != null && currentCost.equipment() > budget;
    }

    public DuelBuildCatalog.Cost currentCost() {
        requireOpen();
        return currentCost;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (int index = 0; index < savedInventory.length; index++) {
            c1.a.R(profile.m + index, savedInventory[index]);
        }
        c1.a.R(profile.d, savedFlags);
        c1.a.R(profile.f, savedCredits);
        c1.a.R(profile.h, savedLevel);
        closed = true;
        if (active == this) active = null;
        builder.z1.f = savedStatsVisible;
        builder.z1.v(savedStatsEnabled);
        for (o3.c row : builder.y1) {
            if (row instanceof o3.p) { ((o3.p) row).C = -1; ((o3.p) row).D = -1; }
            if (row instanceof o3.s) { ((o3.s) row).C = -1; ((o3.s) row).D = -1; }
        }
        refreshInventory();
    }

    private int[] saveInventory() {
        int words = ((profile.l * 6) + 31) / 32;
        int[] values = new int[words];
        for (int index = 0; index < words; index++) {
            values[index] = c1.a.s(profile.m + index);
        }
        return values;
    }

    private void refreshInventory() {
        builder.f1(false);
        builder.c1();
        builder.e1();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("duel profile is closed");
        }
    }
}
