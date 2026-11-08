package redcon.desktop;

import o3.f2;
import o3.k0;
import redcon.duel.DuelSession;
import redcon.net.DuelRuntime;

/**
 * The one battle currently entitled to author native events.
 *
 * <p>The patched call sites in {@code o3.v1.D0} and {@code o3.p0.k} are static:
 * they fire for every shot in every battle the process runs, campaign missions
 * included. This is what decides whether a given shot belongs to a live duel,
 * and it exists as its own class - rather than as more static state inside
 * {@link DuelBattleFlow} - so a headless check can bind a real battle to a
 * recording runtime and exercise the same path the product uses.</p>
 *
 * <p>Nothing here is synchronized: every caller is the game thread, inside a
 * frame, and adding a lock around the simulation's hot path would be a cost
 * paid on every campaign shot to protect against a caller that cannot
 * exist.</p>
 */
public final class DuelBattleAuthority {

    private static DuelBattleController controller;
    private static DuelRuntime runtime;

    private DuelBattleAuthority() {
    }

    /** Take authorship for one armed battle. Replaces any previous binding. */
    public static void bind(DuelBattleController battle, DuelRuntime owner) {
        if (battle == null || owner == null) {
            throw new IllegalArgumentException(
                "battle controller and runtime are required");
        }
        controller = battle;
        runtime = owner;
    }

    /** Release authorship. A mismatched controller is ignored, not an error. */
    public static void unbind(DuelBattleController battle) {
        if (controller == battle) {
            controller = null;
            runtime = null;
        }
    }

    public static boolean bound() {
        return controller != null;
    }

    /** Identify a launched shot and, when it is ours, put it on the wire. */
    public static void launched(k0 ordnance) {
        if (!live()) {
            return;
        }
        controller.launched(ordnance, runtime);
    }

    /** Report damage the shipped simulation just did to our own fortress. */
    public static void damaged(f2 target, float amount, int damageType,
                               k0 ordnance) {
        if (!live()) {
            return;
        }
        controller.damaged(target, amount, damageType, ordnance, runtime);
    }

    /**
     * Damage to our own fortress that arrived without a nameable shot.
     *
     * <p>Counted rather than transmitted. It is real damage and it does reach
     * the peer - through the defender's periodic {@code FORT_SYNC}, which
     * carries resulting structure rather than a difference - but it cannot be
     * reported as an {@code IMPACT} without inventing an attribution.</p>
     */
    public static void unattributed(f2 target, float amount) {
        if (!live()) {
            return;
        }
        controller.unattributedDamage(target, amount, runtime);
    }

    private static boolean live() {
        if (controller == null || runtime == null) {
            return false;
        }
        DuelSession session = runtime.session();
        return session != null && session.phase() == DuelSession.Phase.BATTLE;
    }
}
