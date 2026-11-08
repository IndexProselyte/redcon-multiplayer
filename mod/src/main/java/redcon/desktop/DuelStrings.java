package redcon.desktop;

/**
 * Every label the duel puts on screen, looked up through REDCON's own table.
 *
 * <p>{@code mojo.Base.NLS} hashes the key into {@code c1.a.A} and returns the
 * key itself when there is no entry, which is how the shipped game degrades to
 * English. Routing the duel's labels through it means a locale can translate
 * them by adding entries, and means no screen carries a literal that a
 * translator cannot reach.</p>
 *
 * <p>{@link #KEYS} is the authoritative list. {@code DuelStringsCheck} holds it
 * to the labels the screens actually ask for, and the display matrix uses it to
 * install a pseudo-locale of deliberately long strings so layout problems show
 * up without inventing translations nobody has reviewed.</p>
 */
public final class DuelStrings {

    public static final String MULTIPLAYER = "MULTIPLAYER";
    public static final String ACADEMY_DRILL =
        "Artillery Doctrine School. Cadet live-fire exercises under General Kranz.";

    // Browse
    public static final String DEVELOPMENT = "DEVELOPMENT";
    public static final String LOCAL_STAND_IN = "LOCAL STAND-IN MATCH";
    public static final String NETWORK = "NETWORK";
    public static final String NO_GAMES = "NO GAMES ON THIS NETWORK";
    public static final String SEARCHING = "SEARCHING FOR GAMES";
    public static final String HOST_GAME = "HOST A GAME";
    public static final String WAITING_FOR_PLAYER = "WAITING FOR A PLAYER";
    public static final String JOINING = "JOINING";
    public static final String DECLINED = "THAT GAME DECLINED";
    public static final String BUSY = "THAT GAME IS FULL";
    public static final String NOT_HOSTING = "NOT HOSTING";
    public static final String HOST_OPEN = "HOSTING - JOIN";
    public static final String CONNECTION_LOG = "CONNECTION LOG";
    public static final String REQUEST_SENT = "REQUEST SENT";
    public static final String RETRYING = "RETRYING";
    public static final String HOST_ACCEPTED = "HOST ACCEPTED";
    public static final String CONNECTION_CONFIRMED = "CONNECTION CONFIRMED";
    public static final String DEVICE_LEFT = "DEVICE NO LONGER VISIBLE";
    public static final String HOST_THEN_JOIN = "ONE PLAYER HOSTS. THE OTHER JOINS.";
    public static final String DEVICES = "DEVICES";
    public static final String YOUR_FORTRESS = "YOUR FORTRESS";
    public static final String LEFT_SIDE = "LEFT SIDE";
    public static final String RIGHT_SIDE = "RIGHT SIDE";
    public static final String INCOMPATIBLE = "INCOMPATIBLE VERSION";
    public static final String UNREACHABLE = "NO ANSWER";
    public static final String PROTOCOL_MISMATCH = "DIFFERENT PROTOCOL";
    public static final String BUILD_MISMATCH = "DIFFERENT BUILD";
    public static final String ASSET_MISMATCH = "DIFFERENT GAME FILES";
    public static final String CONNECTION_LOST = "CONNECTION LOST";
    public static final String SYNCHRONIZATION_LOST = "SYNCHRONIZATION LOST";
    public static final String OPPONENT_LEFT = "OPPONENT LEFT THE DUEL";
    public static final String WAITING_FOR_OPPONENT = "WAITING FOR OPPONENT";

    // Pairing
    public static final String COMMANDERS = "COMMANDERS";
    public static final String RULES = "RULES";
    public static final String BUDGET = "BUDGET";
    public static final String BUILD_TIME = "BUILD TIME";
    public static final String MATCH_LIMIT = "MATCH LIMIT";
    public static final String BEGIN_BUILD = "BEGIN BUILD";

    // Commanders
    public static final String PLAYER_ONE = "PLAYER ONE";
    public static final String STAND_IN = "STAND-IN OPPONENT";

    // Build
    public static final String READY = "READY";
    public static final String BUILD = "BUILD";
    public static final String OPPONENT_READY = "OPPONENT READY";
    public static final String OPPONENT_BUILDING = "OPPONENT BUILDING";
    public static final String LOAD_LAST = "LOAD LAST";
    public static final String YOUR_PREVIOUS_FORTRESS = "YOUR PREVIOUS FORTRESS";
    public static final String LEAVE_DUEL = "LEAVE DUEL";

    // Commencing
    public static final String FORTRESSES_LOCKED = "FORTRESSES LOCKED";
    public static final String COMMENCING_IN = "COMMENCING IN";

    // Battle and result
    public static final String SESSION = "SESSION";
    public static final String WINS = "WINS";
    public static final String DRAW = "DRAW";
    public static final String REMATCH = "REMATCH";
    public static final String REMATCH_STARTING = "REMATCH STARTING";
    public static final String WAITING_FOR = "WAITING FOR";
    public static final String REMATCH_WHEN_BOTH_READY = "REMATCH WHEN BOTH ARE READY";

    /** Every key the duel looks up. Keep in step with the constants above. */
    public static final String[] KEYS = {
        MULTIPLAYER, ACADEMY_DRILL,
        DEVELOPMENT, LOCAL_STAND_IN, NETWORK, NO_GAMES,
        SEARCHING, HOST_GAME, WAITING_FOR_PLAYER, JOINING, DECLINED, BUSY, NOT_HOSTING,
        HOST_OPEN, CONNECTION_LOG, REQUEST_SENT, RETRYING, HOST_ACCEPTED,
        CONNECTION_CONFIRMED, DEVICE_LEFT, HOST_THEN_JOIN, DEVICES, YOUR_FORTRESS,
        LEFT_SIDE, RIGHT_SIDE,
        INCOMPATIBLE, UNREACHABLE, PROTOCOL_MISMATCH, BUILD_MISMATCH,
        ASSET_MISMATCH, CONNECTION_LOST, SYNCHRONIZATION_LOST,
        OPPONENT_LEFT, WAITING_FOR_OPPONENT,
        COMMANDERS, RULES, BUDGET, BUILD_TIME, MATCH_LIMIT, BEGIN_BUILD,
        PLAYER_ONE, STAND_IN,
        READY, BUILD, OPPONENT_READY, OPPONENT_BUILDING, LOAD_LAST,
        YOUR_PREVIOUS_FORTRESS, LEAVE_DUEL,
        FORTRESSES_LOCKED, COMMENCING_IN,
        SESSION, WINS, DRAW, REMATCH, REMATCH_STARTING, WAITING_FOR,
        REMATCH_WHEN_BOTH_READY,
    };

    private DuelStrings() {
    }

    /** The localized label for this key, or the key when nothing translates it. */
    public static String of(String key) {
        return mojo.Base.NLS(key);
    }

    /**
     * Add or replace a translation in the table REDCON's own {@code NLS} reads.
     *
     * <p>The table is absent until something needs it, which is why an English
     * build resolves every key to itself. Creating it does not disturb the
     * game's own keys: they are still missing from it and still fall through.
     * </p>
     */
    public static void translate(String key, String text) {
        if (c1.a.A == null) {
            c1.a.A = new j3.e(128);
        }
        c1.a.A.b(c1.a.y(key), text);
    }
}
