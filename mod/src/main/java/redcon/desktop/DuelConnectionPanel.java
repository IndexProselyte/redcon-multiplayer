package redcon.desktop;

import java.util.Collections;
import java.util.List;

/** Scroll/input and export are presentation-only, on both platforms. */
final class DuelConnectionPanel extends i3.q {
    private final DuelConnectionStatus status;
    private final DuelUiText title = new DuelUiText(h3.c.o);
    private final DuelUiText[] rows = new DuelUiText[32];
    private final DuelUiText[] buttons = new DuelUiText[4];
    private final DuelUiText feedback = new DuelUiText(h3.c.X);
    private List<DuelAppLog.Entry> history = Collections.emptyList();
    private long revision = -1, anchor;
    private int offset, lastY, downX, downY;
    private boolean dragged;

    DuelConnectionPanel(DuelConnectionStatus status) {
        this.status = status;
        v(true);
        for (int n = 0; n < rows.length; n++) rows[n] = new DuelUiText(h3.c.X);
        for (int n = 0; n < buttons.length; n++) buttons[n] = new DuelUiText(h3.c.X);
    }
    private int capacity() { return Math.min(rows.length, Math.max(1, (j - 146) / 20)); }
    private void refresh() {
        if (revision == DuelAppLog.revision()) return;
        revision = DuelAppLog.revision();
        history = DuelAppLog.entries();
        if (offset > 0 && anchor != 0) {
            int end = 0;
            while (end < history.size() && history.get(end).id <= anchor) end++;
            offset = history.size() - Math.max(capacity(), end);
        }
        offset = Math.max(0, Math.min(offset, history.size() - capacity()));
    }
    void scroll(int lines) {
        refresh();
        offset = Math.max(0, Math.min(Math.max(0, history.size() - capacity()), offset + lines));
        int end = history.size() - offset;
        anchor = end == 0 ? 0 : history.get(end - 1).id;
    }
    int scrollOffset() { return offset; }
    @Override public void p(int x, int y) { downX = x; downY = lastY = y; dragged = false; }
    @Override public void o(int x, int y) {
        if (Math.abs(y - downY) > 8 || Math.abs(x - downX) > 8) dragged = true;
        if (downY >= h + 70 && downY < h + j - 66 && Math.abs(y - lastY) >= 20) {
            int lines = (y - lastY) / 20;
            scroll(lines);
            lastY += lines * 20;
        }
    }
    @Override public void q(int x, int y) {
        if (dragged || !d(x, y) || y < h + j - 64 || y >= h + j - 26
                || downY < h + j - 64 || downY >= h + j - 26) return;
        int part = part(x);
        if (part != part(downX)) return;
        if (part == 0) scroll(capacity());
        else if (part == 1) scroll(-capacity());
        else DuelLogActions.perform(part == 2);
    }
    private int part(int x) { return Math.min(3, Math.max(0, (x - g - 12) * 4 / Math.max(1, i - 24))); }

    @Override public void n() {
        refresh();
        int accent = status.failed() ? DuelConnectionStatus.ALERT : 0xffffcb78;
        mojo.b0.b(0xe51b2025, g, h, i, j);
        title.draw(DuelStrings.of(DuelStrings.CONNECTION_LOG), g + 18, h + 16, i - 36, -1);
        int barWidth = i - 36;
        int stage = status.stage();
        for (int n = 0; n < 3; n++) {
            int x = g + 18 + n * barWidth / 3;
            mojo.b0.b(n < stage ? DuelConnectionStatus.GOOD : 0xff3b4247, x, h + 56, barWidth / 3 - 4, 4);
        }
        if (!status.failed() && stage < 3) {
            int travel = Math.max(1, barWidth / 3 - 24);
            int shift = (int) ((System.nanoTime() / 8_000_000L) % travel);
            mojo.b0.b(accent, g + 18 + stage * barWidth / 3 + shift, h + 56, 20, 4);
        }
        int end = history.size() - offset;
        int first = Math.max(0, end - capacity());
        for (int n = first; n < end; n++) {
            DuelAppLog.Entry entry = history.get(n);
            String text = entry.text.length() > 24 && entry.text.charAt(4) == '-'
                && entry.text.charAt(10) == ' ' ? entry.text.substring(11) : entry.text;
            rows[n - first].draw(text, g + 18, h + 76 + 20 * (n - first), i - 46, entry.colour, .75f);
        }
        if (history.size() > capacity()) {
            int track = capacity() * 20;
            int thumb = Math.max(12, track * capacity() / history.size());
            int pos = (track - thumb) * first / Math.max(1, history.size() - capacity());
            mojo.b0.b(0xff3b4247, g + i - 12, h + 70, 3, track);
            mojo.b0.b(DuelConnectionStatus.NORMAL, g + i - 12, h + 70 + pos, 3, thumb);
        }
        String[] labels = {"UP", "DOWN", "COPY", "SAVE .TXT"};
        int width = (i - 24) / 4;
        for (int n = 0; n < 4; n++) {
            int x = g + 12 + width * n;
            mojo.b0.b(0xff343c42, x, h + j - 64, width - 4, 36);
            buttons[n].draw(labels[n], x + 7, h + j - 51, width - 16, -1, .75f);
        }
        String message = DuelLogActions.feedback();
        if (message.isEmpty()) message = offset == 0 ? "LATEST - DRAG OR SCROLL FOR HISTORY" : "HISTORY - DOWN TO RETURN TO LATEST";
        feedback.draw(message, g + 14, h + j - 18, i - 28, DuelConnectionStatus.NORMAL, .65f);
    }
}
