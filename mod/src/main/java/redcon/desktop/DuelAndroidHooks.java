package redcon.desktop;

/** Static entry points injected into the shipped Android frame pump. */
public final class DuelAndroidHooks {

    /** Also captures exceptions the native engine catches instead of rethrowing. */
    public static void reportError(Throwable failure) {
        AndroidDuelLogActions.start();
        DuelAppLog.error("Native engine", failure);
    }

    /** Android must use the same seated input context as the desktop. */
    public static void dispatchInput(mojo.GameLoop loop) {
        DuelBattleFlow.dispatchNativeInput(loop::dispatchInput);
    }

    /** Advance committed duel time and native simulation before REDCON paints. */
    public static void beforeFrame() {
        AndroidDuelLogActions.start();
        // Frames, plural: this pump runs once per rendered frame, and a
        // rendered frame is not a simulation frame on a device that does not
        // draw at 60 Hz.
        DuelBattleFlow.beforeProductFrames();
    }

    /** Restore paint-only state and advance the Build countdown after a frame. */
    public static void afterFrame() {
        DuelBattleFlow.afterProductFrame();
        DuelBuildFlow.updateActive(Duel.TICKS_PER_FRAME);
        DuelNetwork.updateActive();
    }

    private DuelAndroidHooks() {
    }
}
