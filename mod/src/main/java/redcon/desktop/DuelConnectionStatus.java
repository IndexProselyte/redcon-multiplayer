package redcon.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import redcon.net.DuelLobby;

/** Bounded local UI history. Observing a snapshot never pumps or authors network traffic. */
final class DuelConnectionStatus {
    static final int LIMIT = DuelAppLog.LIMIT;
    static final int NORMAL = 0xffacb6bd, GOOD = 0xffbadd9b, ALERT = 0xffffa080;
    static final class Entry {
        final String text;
        final int colour;
        Entry(String text, int colour) { this.text = text; this.colour = colour; }
    }
    private Map<Long, String> seen = new LinkedHashMap<>();
    private String previous = "";
    private int attempts;
    private boolean accepted;
    private DuelLobby.State state;

    void observe(DuelLobby.State state, String error, int attempts, boolean accepted,
                 Map<Long, String> peers) {
        this.state = state;
        String status = state == null ? error : state.name() + ":" + error;
        if (!status.equals(previous)) {
            previous = status;
            add(state == null || state == DuelLobby.State.FAILED ? error : label(state),
                state == null || state == DuelLobby.State.FAILED ? ALERT
                    : state == DuelLobby.State.PAIRED ? GOOD : NORMAL);
        }
        if (state == DuelLobby.State.JOINING) {
            if (attempts > this.attempts) add(DuelStrings.of(attempts == 1
                ? DuelStrings.REQUEST_SENT : DuelStrings.RETRYING) + " " + attempts, NORMAL);
            if (accepted && !this.accepted) add(DuelStrings.of(DuelStrings.HOST_ACCEPTED), GOOD);
        }
        this.attempts = attempts;
        this.accepted = accepted;
        for (Map.Entry<Long, String> peer : peers.entrySet()) {
            if (!peer.getValue().equals(seen.get(peer.getKey()))) add(peer.getValue(), NORMAL);
        }
        for (Map.Entry<Long, String> peer : seen.entrySet()) {
            if (!peers.containsKey(peer.getKey())) add(DuelStrings.of(DuelStrings.DEVICE_LEFT), NORMAL);
        }
        seen = new LinkedHashMap<>(peers);
    }

    private void add(String text, int colour) {
        if (text == null || text.isEmpty()) return;
        DuelAppLog.record(text, colour);
    }

    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (DuelAppLog.Entry entry : DuelAppLog.entries()) result.add(new Entry(entry.text, entry.colour));
        return result;
    }
    boolean failed() { return state == null || state == DuelLobby.State.FAILED; }
    int stage() { return state == DuelLobby.State.PAIRED ? 3
        : state == DuelLobby.State.JOINING ? (accepted ? 2 : 1) : 0; }
    String heading() { return state == null ? DuelStrings.of(DuelStrings.CONNECTION_LOST) : label(state); }
    private static String label(DuelLobby.State state) {
        switch (state) {
            case HOSTING: return DuelStrings.of(DuelStrings.WAITING_FOR_PLAYER);
            case JOINING: return DuelStrings.of(DuelStrings.JOINING);
            case PAIRED: return DuelStrings.of(DuelStrings.CONNECTION_CONFIRMED);
            case FAILED: return DuelStrings.of(DuelStrings.CONNECTION_LOST);
            default: return DuelStrings.of(DuelStrings.SEARCHING);
        }
    }
}
