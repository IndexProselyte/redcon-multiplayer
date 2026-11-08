package redcon.desktop;

/** Platform IO is installed at startup; buttons never touch match state. */
public final class DuelLogActions {
    public interface Platform {
        void copy(String text) throws Exception;
        String save(String text) throws Exception;
    }
    private static Platform platform;
    private static volatile boolean busy;
    private static volatile String feedback = "";
    public static void install(Platform value) { platform = value; }
    static String feedback() { return feedback; }
    static boolean busy() { return busy; }
    static synchronized void perform(boolean copy) {
        if (busy) return;
        if (platform == null) { feedback = "EXPORT UNAVAILABLE"; return; }
        busy = true;
        feedback = copy ? "COPYING..." : "SAVING...";
        String snapshot = DuelAppLog.exportText();
        Thread worker = new Thread(() -> {
            try {
                if (copy) { platform.copy(snapshot); feedback = "COPIED"; }
                else feedback = "SAVED: " + platform.save(snapshot);
                DuelAppLog.record(feedback, DuelConnectionStatus.GOOD);
            } catch (Exception failure) {
                feedback = copy ? "COPY FAILED - TRY SAVE LOG" : "SAVE FAILED - SEE LOG";
                DuelAppLog.error(feedback, failure);
            } finally { busy = false; }
        }, "redcon-log-export");
        worker.setDaemon(true);
        worker.start();
    }
    private DuelLogActions() { }
}
