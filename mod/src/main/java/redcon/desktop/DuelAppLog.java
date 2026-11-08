package redcon.desktop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Process-wide, bounded diagnostics. Never reads or advances the simulation. */
public final class DuelAppLog {
    // Keep the complete exported text comfortably below Android's Binder clipboard limit.
    static final int LIMIT = 8192, MAX_CHARS = 192 * 1024;
    private static final ArrayDeque<Entry> history = new ArrayDeque<>();
    private static final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(2048);
    private static long serial, discarded, diskDropped;
    private static int chars;
    private static boolean installed;
    private static File journal;
    private static String storageError = "";
    public static final class Entry {
        public final long id;
        public final String text;
        public final int colour;
        Entry(long id, String text, int colour) { this.id = id; this.text = text; this.colour = colour; }
    }
    public static synchronized void install(File file) {
        if (installed) return;
        installed = true;
        journal = file;
        // Retain the previous run (including fatal exceptions) for export after restart.
        if (file != null && file.isFile()) {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                long offset = Math.max(0, in.length() - MAX_CHARS / 2);
                in.seek(offset);
                byte[] bytes = new byte[(int) (in.length() - offset)];
                in.readFully(bytes);
                String saved = new String(bytes, StandardCharsets.UTF_8);
                if (offset > 0) saved = saved.substring(saved.indexOf('\n') + 1);
                for (String line : saved.split("\n")) append(line, colour(line), false);
            } catch (IOException failure) { storageError = failure.toString(); }
        }
        PrintStream original = System.err;
        try { System.setErr(new PrintStream(new Tee(original), true, "UTF-8")); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            error("Uncaught exception on " + thread.getName(), failure);
            flushFatal();
            if (previous != null) previous.uncaughtException(thread, failure);
            else failure.printStackTrace(original);
        });
        if (file != null) {
            Thread writer = new Thread(() -> {
                while (true) {
                    try { writeBatch(pending.take()); }
                    catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
                    catch (IOException failure) { synchronized (DuelAppLog.class) { storageError = failure.toString(); } }
                }
            }, "redcon-diagnostic-writer");
            writer.setDaemon(true);
            writer.start();
        }
        record("SESSION " + DuelIdentity.BUILD_VERSION + " | " + System.getProperty("os.name")
            + " " + System.getProperty("os.arch") + " | Java " + System.getProperty("java.version"), 0xffbadd9b);
    }
    public static void error(String context, Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        record("ERROR " + context + "\n" + trace, 0xffffa080);
    }
    public static synchronized void record(String text, int colour) {
        if (text == null || text.isEmpty()) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        for (String line : text.replace("\r", "").split("\n")) {
            // Split unusually large lines rather than discarding diagnostic content.
            for (int start = 0; start < line.length(); start += 4096)
                append(time + " " + line.substring(start, Math.min(line.length(), start + 4096)), colour, true);
        }
    }
    private static void append(String text, int colour, boolean persist) {
        Entry entry = new Entry(++serial, text, colour);
        history.addLast(entry);
        chars += text.length();
        while (history.size() > LIMIT || chars > MAX_CHARS) {
            chars -= history.removeFirst().text.length();
            discarded++;
        }
        if (persist && journal != null && !pending.offer(text + "\n")) diskDropped++;
    }
    public static synchronized List<Entry> entries() { return new ArrayList<>(history); }
    public static synchronized long revision() { return serial; }
    public static synchronized String exportText() {
        StringBuilder out = new StringBuilder("REDCON diagnostic log\nBuild: " + DuelIdentity.BUILD_VERSION
            + "\nLocal timezone: " + TimeZone.getDefault().getID() + "\nOld lines evicted: " + discarded
            + "\nJournal queue drops: " + diskDropped + "\n");
        if (!storageError.isEmpty()) out.append("Journal error: ").append(storageError).append('\n');
        for (Entry entry : history) out.append(entry.text).append('\n');
        return out.toString();
    }
    private static final Object DISK = new Object();
    private static void writeBatch(String first) throws IOException {
        synchronized (DISK) {
            File parent = journal.getAbsoluteFile().getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
            boolean rotate = journal.length() > 4L * MAX_CHARS;
            try (FileOutputStream out = new FileOutputStream(journal, !rotate)) {
                // Rotation writes a bounded memory snapshot so the most recent failure survives.
                String text = first;
                if (rotate) {
                    synchronized (DuelAppLog.class) {
                        text = exportText();
                        pending.clear(); // These queued entries are already in the snapshot.
                    }
                }
                out.write(text.getBytes(StandardCharsets.UTF_8));
                for (int n = 0; !rotate && n < 256; n++) {
                    String line = pending.poll();
                    if (line == null) break;
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }
    private static void flushFatal() {
        if (journal == null) return;
        try { writeBatch(exportText()); } catch (IOException ignored) { /* Preserve original crash handling. */ }
    }
    private static int colour(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error") || lower.contains("exception") || lower.contains("desync")
            || lower.contains("duel link") || lower.contains("failed") ? 0xffffa080 : 0xffacb6bd;
    }
    private static final class Tee extends OutputStream {
        private final PrintStream original;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        Tee(PrintStream original) { this.original = original; }
        @Override public synchronized void write(int value) {
            original.write(value);
            accept(value);
        }
        private void accept(int value) {
            if (value == '\n') emit();
            else {
                // Emit before the next UTF-8 character, never in its continuation bytes.
                if (line.size() >= 16384 && (value & 0xc0) != 0x80) emit();
                line.write(value);
            }
        }
        @Override public synchronized void write(byte[] bytes, int off, int len) {
            original.write(bytes, off, len);
            for (int n = off; n < off + len; n++) accept(bytes[n] & 255);
        }
        private void emit() {
            String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
            line.reset();
            record(text, colour(text));
        }
        @Override public void flush() { original.flush(); }
    }
    private DuelAppLog() { }
}
