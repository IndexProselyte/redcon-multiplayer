package redcon.net;

import redcon.duel.FortDesign;
import redcon.duel.DuelSession;

/**
 * Applies one ordered committed history to the product duel session.
 *
 * <p>Session-level state is applied here for local and remote authors alike.
 * Native battle values are delegated through {@link DuelBattleEventConsumer};
 * that adapter is the only remaining place allowed to touch live REDCON battle
 * objects.</p>
 */
public final class DuelSessionEventConsumer implements DuelEventConsumer {

    private final DuelSession session;
    private final DuelPeerState state;
    private final DuelBattleEventConsumer battle;

    public DuelSessionEventConsumer(int localPlayer, DuelSession session,
                                    DuelBattleEventConsumer battle) {
        this(localPlayer, session, battle, 0);
    }

    /**
     * Adopt a history already in progress.
     *
     * <p>A networked duel settles its rules before there is a session to apply
     * them to: the two clients exchange proposals in Pairing and the session is
     * constructed from the committed revision when the host starts Build. The
     * events before that point were reduced by the runtime's own pre-session
     * reducer, so this consumer starts from that sequence rather than treating
     * the rest of the history as one large gap.</p>
     */
    public DuelSessionEventConsumer(int localPlayer, DuelSession session,
                                    DuelBattleEventConsumer battle,
                                    long startSequence) {
        if (session == null || battle == null) {
            throw new IllegalArgumentException(
                "duel session and battle consumer are required");
        }
        if (startSequence < 0) {
            throw new IllegalArgumentException("negative start sequence");
        }
        this.session = session;
        this.state = new DuelPeerState(localPlayer);
        this.state.skipTo(startSequence);
        this.battle = battle;
    }

    @Override
    public synchronized void accept(CommittedDuelEvent committed) {
        if (committed == null) {
            throw new IllegalArgumentException("committed event is null");
        }
        long sequence = committed.sequence();
        if (sequence <= state.lastSequence()) {
            return;
        }
        if (sequence != state.lastSequence() + 1) {
            throw new IllegalStateException("event gap: expected "
                + (state.lastSequence() + 1) + ", received " + sequence);
        }

        DuelEvent event = committed.event();
        apply(event);
        // This cannot fail after the identical sequence check above. Keeping
        // the reducer beside the product session exposes a replayable snapshot
        // without creating a second application path.
        state.accept(committed);
    }

    private void apply(DuelEvent event) {
        switch (event.kind()) {
            case RULES_PROPOSE:
                throw new IllegalArgumentException(
                    "rule proposals must be resolved before commit");
            case RULES_SET:
                applyRules((DuelEvent.RulesSet) event);
                return;
            case BUILD_COMMIT: {
                DuelEvent.BuildCommit build = (DuelEvent.BuildCommit) event;
                session.select(build.player, build.design());
                return;
            }
            case READY_SET: {
                DuelEvent.ReadySet ready = (DuelEvent.ReadySet) event;
                session.setReady(ready.player, ready.ready);
                return;
            }
            case PHASE_CLOCK:
                applyClock((DuelEvent.PhaseClock) event);
                return;
            case ORDER:
                battle.applyOrder((DuelEvent.OrderIssued) event.copy());
                return;
            case FIRE:
                battle.applyFire((DuelEvent.Fire) event.copy());
                return;
            case IMPACT:
                battle.applyImpact((DuelEvent.Impact) event.copy());
                return;
            case FORT_SYNC:
                battle.applyFortSync((DuelEvent.FortSync) event.copy());
                return;
            case DEFEATED: {
                // Evidence only. ROUND_RESULT carries the exact authoritative
                // ending after the host resolves defeat or the Battle clock.
                return;
            }
            case ROUND_RESULT: {
                DuelEvent.RoundResult result = (DuelEvent.RoundResult) event;
                session.finish(new DuelSession.RoundResult(
                    result.winner, result.ending, result.seconds));
                return;
            }
            case REMATCH_READY: {
                DuelEvent.RematchReady ready =
                    (DuelEvent.RematchReady) event;
                session.setRematchReady(ready.player, ready.ready);
                return;
            }
            case LEAVE: {
                DuelEvent.Leave leave = (DuelEvent.Leave) event;
                session.leave(leave.player);
                return;
            }
            default:
                throw new IllegalArgumentException(
                    "unsupported committed event " + event.kind());
        }
    }

    private void applyRules(DuelEvent.RulesSet rules) {
        if (session.creditBudget() != rules.creditBudget
                || session.buildSeconds() != rules.buildSeconds
                || session.matchSeconds() != rules.matchSeconds) {
            throw new IllegalStateException(
                "committed rules do not match the constructed duel session");
        }
    }

    private void applyClock(DuelEvent.PhaseClock clock) {
        if (clock.round != session.round()) {
            throw new IllegalStateException("clock round " + clock.round
                + " does not match session round " + session.round());
        }
        switch (clock.phase) {
            case BUILD:
                requirePhase(DuelSession.Phase.BUILD, clock.phase);
                return;
            case COMMENCING:
                requirePhase(DuelSession.Phase.COMMENCING, clock.phase);
                return;
            case BATTLE:
                if (session.phase() == DuelSession.Phase.COMMENCING) {
                    session.advance(session.commencingTicksRemaining());
                }
                requirePhase(DuelSession.Phase.BATTLE, clock.phase);
                return;
            default:
                throw new IllegalArgumentException(
                    "unsupported committed phase clock " + clock.phase);
        }
    }

    private void requirePhase(DuelSession.Phase expected,
                              DuelEvent.ClockPhase clock) {
        if (session.phase() != expected) {
            throw new IllegalStateException("clock " + clock
                + " cannot apply while session is " + session.phase());
        }
    }

    public DuelSession session() { return session; }

    public long lastSequence() { return state.lastSequence(); }

    public FortDesign committedFort(int player) {
        return state.committedFort(player);
    }

    public boolean ready(int player) { return state.ready(player); }

    public boolean rematchReady(int player) {
        return state.rematchReady(player);
    }
}
