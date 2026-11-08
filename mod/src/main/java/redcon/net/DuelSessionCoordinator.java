package redcon.net;

import java.util.HashMap;
import java.util.Map;
import redcon.duel.Side;

/**
 * Host authority between client-authored events and the committed channel.
 *
 * <p>It validates who may author each value, turns rule proposals into
 * monotonic complete rule revisions, freezes rules when Build starts, and
 * admits phase-specific events only in their phase. It contains no socket or
 * widget code and is therefore shared by the in-process and later UDP hosts.</p>
 */
public final class DuelSessionCoordinator implements DuelEventSink {

    public enum Phase {
        PAIRING,
        BUILD,
        COMMENCING,
        BATTLE,
        RESULT,
        CLOSED
    }

    private final int hostPlayer;
    private final DuelEventSink committed;
    private final boolean[] hasBuild = new boolean[2];
    private final boolean[] ready = new boolean[2];
    private final boolean[] rematchReady = new boolean[2];
    private final boolean[] defeated = new boolean[2];
    private final Map<Long, Integer> shotAttackers = new HashMap<>();

    private DuelEvent.RulesSet rules;
    private long nextRulesRevision = 1;
    private Phase phase = Phase.PAIRING;
    private int round;

    public DuelSessionCoordinator(int hostPlayer, DuelEventSink committed) {
        DuelEvent.requirePlayer(hostPlayer);
        if (committed == null) {
            throw new IllegalArgumentException("committed event sink is null");
        }
        this.hostPlayer = hostPlayer;
        this.committed = committed;
    }

    @Override
    public synchronized void publish(int sourcePlayer, DuelEvent event) {
        DuelEvent.requirePlayer(sourcePlayer);
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("duel session is closed");
        }

        switch (event.kind()) {
            case RULES_PROPOSE:
                proposeRules(sourcePlayer, (DuelEvent.RulesPropose) event);
                return;
            case RULES_SET:
                throw new IllegalArgumentException(
                    "clients propose rules; only the coordinator sets them");
            case BUILD_COMMIT: {
                requirePhase(Phase.BUILD);
                DuelEvent.BuildCommit build = (DuelEvent.BuildCommit) event;
                requireAuthor(sourcePlayer, build.player);
                hasBuild[build.player] = true;
                ready[build.player] = false;
                break;
            }
            case READY_SET: {
                requirePhase(Phase.BUILD);
                DuelEvent.ReadySet set = (DuelEvent.ReadySet) event;
                requireAuthor(sourcePlayer, set.player);
                if (set.ready && !hasBuild[set.player]) {
                    throw new IllegalStateException(
                        "player must commit a fort before becoming ready");
                }
                ready[set.player] = set.ready;
                break;
            }
            case PHASE_CLOCK:
                applyClock(sourcePlayer, (DuelEvent.PhaseClock) event);
                break;
            case ORDER: {
                requirePhase(Phase.BATTLE);
                DuelEvent.OrderIssued order = (DuelEvent.OrderIssued) event;
                requireAuthor(sourcePlayer, order.player);
                break;
            }
            case FIRE: {
                requirePhase(Phase.BATTLE);
                DuelEvent.Fire fire = (DuelEvent.Fire) event;
                requireAuthor(sourcePlayer, fire.attacker);
                if (shotAttackers.putIfAbsent(fire.shotId, fire.attacker) != null) {
                    throw new IllegalStateException("duplicate shot id " + fire.shotId);
                }
                break;
            }
            case IMPACT: {
                requirePhase(Phase.BATTLE);
                DuelEvent.Impact impact = (DuelEvent.Impact) event;
                requireAuthor(sourcePlayer, impact.defender);
                Integer attacker = shotAttackers.get(impact.shotId);
                if (attacker == null || attacker.intValue() == impact.defender) {
                    throw new IllegalStateException(
                        "impact does not match an opposing fired shot " + impact.shotId);
                }
                break;
            }
            case FORT_SYNC: {
                requirePhase(Phase.BATTLE);
                DuelEvent.FortSync sync = (DuelEvent.FortSync) event;
                requireAuthor(sourcePlayer, sync.player);
                break;
            }
            case DEFEATED: {
                requirePhase(Phase.BATTLE);
                DuelEvent.Defeated loss = (DuelEvent.Defeated) event;
                requireAuthor(sourcePlayer, loss.player);
                if (defeated[loss.player]) {
                    throw new IllegalStateException(
                        "duplicate defeat for player " + loss.player);
                }
                defeated[loss.player] = true;
                break;
            }
            case ROUND_RESULT: {
                requirePhase(Phase.BATTLE);
                if (sourcePlayer != hostPlayer) {
                    throw new IllegalArgumentException(
                        "only the host commits a round result");
                }
                validateResult((DuelEvent.RoundResult) event);
                phase = Phase.RESULT;
                break;
            }
            case REMATCH_READY: {
                requirePhase(Phase.RESULT);
                DuelEvent.RematchReady set = (DuelEvent.RematchReady) event;
                requireAuthor(sourcePlayer, set.player);
                rematchReady[set.player] = set.ready;
                break;
            }
            case LEAVE: {
                DuelEvent.Leave leave = (DuelEvent.Leave) event;
                requireAuthor(sourcePlayer, leave.player);
                phase = Phase.CLOSED;
                break;
            }
            default:
                throw new IllegalArgumentException("unsupported event " + event.kind());
        }

        committed.publish(sourcePlayer, event);
    }

    private void proposeRules(int sourcePlayer, DuelEvent.RulesPropose proposal) {
        requirePhase(Phase.PAIRING);
        requireAuthor(sourcePlayer, proposal.player);
        rules = new DuelEvent.RulesSet(
            nextRulesRevision++, proposal.player, proposal.creditBudget,
            proposal.buildSeconds, proposal.matchSeconds);
        // The host authors the accepted ordering decision; editorPlayer retains
        // who made the proposal displayed by both clients.
        committed.publish(hostPlayer, rules);
    }

    private void applyClock(int sourcePlayer, DuelEvent.PhaseClock clock) {
        if (sourcePlayer != hostPlayer) {
            throw new IllegalArgumentException("only the host assigns phase clocks");
        }
        switch (clock.phase) {
            case BUILD:
                if (phase == Phase.PAIRING) {
                    if (rules == null || clock.round != 1) {
                        throw new IllegalStateException(
                            "first Build requires rules and round 1");
                    }
                } else if (phase == Phase.RESULT) {
                    if (!rematchReady[Side.NEAR] || !rematchReady[Side.FAR]
                            || clock.round != round + 1) {
                        throw new IllegalStateException(
                            "next Build requires both rematch votes");
                    }
                } else {
                    throw wrongPhase(Phase.PAIRING, Phase.RESULT);
                }
                round = clock.round;
                clearRoundState();
                phase = Phase.BUILD;
                break;
            case COMMENCING:
                requirePhase(Phase.BUILD);
                requireRound(clock);
                if (!hasBuild[Side.NEAR] || !hasBuild[Side.FAR]
                        || !ready[Side.NEAR] || !ready[Side.FAR]) {
                    throw new IllegalStateException(
                        "Commencing requires two committed ready forts");
                }
                phase = Phase.COMMENCING;
                break;
            case BATTLE:
                requirePhase(Phase.COMMENCING);
                requireRound(clock);
                phase = Phase.BATTLE;
                break;
            default:
                throw new IllegalArgumentException("unsupported clock " + clock.phase);
        }
    }

    private void clearRoundState() {
        hasBuild[Side.NEAR] = false;
        hasBuild[Side.FAR] = false;
        ready[Side.NEAR] = false;
        ready[Side.FAR] = false;
        rematchReady[Side.NEAR] = false;
        rematchReady[Side.FAR] = false;
        shotAttackers.clear();
        defeated[Side.NEAR] = false;
        defeated[Side.FAR] = false;
    }

    private void validateResult(DuelEvent.RoundResult result) {
        switch (result.ending) {
            case DEFEAT:
                if (result.winner == -1 || !defeated[result.winner ^ 1]) {
                    throw new IllegalStateException(
                        "defeat result requires the losing player's declaration");
                }
                return;
            case SIMULTANEOUS:
                if (!defeated[Side.NEAR] || !defeated[Side.FAR]) {
                    throw new IllegalStateException(
                        "simultaneous result requires both defeat declarations");
                }
                return;
            case CLOCK_DAMAGE:
            case CLOCK_ROOMS:
            case CLOCK_DRAW:
                return;
            default:
                throw new IllegalArgumentException(
                    "unsupported round ending " + result.ending);
        }
    }

    private void requireRound(DuelEvent.PhaseClock clock) {
        if (clock.round != round) {
            throw new IllegalArgumentException(
                "clock round " + clock.round + " does not match " + round);
        }
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw wrongPhase(expected);
        }
    }

    private IllegalStateException wrongPhase(Phase... expected) {
        StringBuilder message = new StringBuilder("expected ");
        for (int i = 0; i < expected.length; i++) {
            if (i > 0) {
                message.append(" or ");
            }
            message.append(expected[i]);
        }
        return new IllegalStateException(message.append(", was ")
            .append(phase).toString());
    }

    private static void requireAuthor(int sourcePlayer, int payloadPlayer) {
        if (sourcePlayer != payloadPlayer) {
            throw new IllegalArgumentException("player " + sourcePlayer
                + " cannot author player " + payloadPlayer + " state");
        }
    }

    public synchronized Phase phase() { return phase; }
    public synchronized int round() { return round; }
    public synchronized long rulesRevision() {
        return rules == null ? 0 : rules.revision;
    }
}
