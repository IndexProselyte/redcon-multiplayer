package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import mojo.f;
import o3.z0;
import redcon.duel.FortDesign;
import redcon.duel.Order;

/**
 * Versioned binary format for one committed duel event.
 *
 * <p>Decoding is an untrusted-data boundary: packet size, collection lengths,
 * enum tags, blueprint ids, numeric values, checksum, and trailing bytes are
 * all checked before an event reaches the consumer.</p>
 */
public final class DuelPacketCodec {

    /** v3 adds committed lockstep delay and host-monotonic phase deadlines. */
    public static final int PROTOCOL_VERSION = 3;
    public static final int MAX_PACKET_BYTES = ReliableUdpChannel.MAX_MESSAGE_BYTES;

    private static final int MAGIC = 0x52445034; // "RDP4"
    private static final int REQUEST_MAGIC = 0x52445234; // "RDR4"
    private static final int MAX_ARRAY_ITEMS = 16 * 1024;
    private static final int MAX_EFFECTS = 16;
    private static final int MAX_REASON_BYTES = 1024;

    /** Match identity plus the decoded committed event. */
    public static final class Decoded {
        public final long matchId;
        public final CommittedDuelEvent committed;

        private Decoded(long matchId, CommittedDuelEvent committed) {
            this.matchId = matchId;
            this.committed = committed;
        }
    }

    /** Client-authored request decoded at the host authority boundary. */
    public static final class DecodedRequest {
        public final long matchId;
        public final int sourcePlayer;
        public final DuelEvent event;

        private DecodedRequest(long matchId, int sourcePlayer, DuelEvent event) {
            this.matchId = matchId;
            this.sourcePlayer = sourcePlayer;
            this.event = event;
        }
    }

    public static byte[] encode(long matchId, CommittedDuelEvent committed) {
        if (matchId == 0 || committed == null) {
            throw new IllegalArgumentException("match id and event are required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeShort(PROTOCOL_VERSION);
            out.writeLong(matchId);
            out.writeLong(committed.sequence());
            out.writeByte(committed.sourcePlayer());
            DuelEvent event = committed.event();
            if (event.kind() == DuelEvent.Kind.RULES_PROPOSE) {
                throw new IllegalArgumentException(
                    "rule proposals are requests, not committed events");
            }
            out.writeByte(wireType(event.kind()));
            writeEvent(out, event);
            out.flush();
            if (bytes.size() + Integer.BYTES > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("duel event exceeds packet limit");
            }
            byte[] body = bytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("memory codec failed", impossible);
        }
    }

    public static Decoded decode(byte[] packet) {
        if (packet == null || packet.length < 28
                || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid duel packet length");
        }
        verifyChecksum(packet);
        try {
            DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(packet, 0, packet.length - Integer.BYTES));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("not a Phase 04 duel packet");
            }
            int version = in.readUnsignedShort();
            if (version != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                    "unsupported duel protocol " + version);
            }
            long matchId = in.readLong();
            if (matchId == 0) {
                throw new IllegalArgumentException("zero match id");
            }
            long sequence = in.readLong();
            int sourcePlayer = in.readUnsignedByte();
            int type = in.readUnsignedByte();
            DuelEvent event = readEvent(in, type);
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing duel packet bytes");
            }
            return new Decoded(matchId,
                new CommittedDuelEvent(sequence, sourcePlayer, event));
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated duel packet", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid duel packet", failed);
        }
    }

    public static byte[] encodeRequest(long matchId, int sourcePlayer,
                                       DuelEvent event) {
        if (matchId == 0 || event == null) {
            throw new IllegalArgumentException("match id and request are required");
        }
        DuelEvent.requirePlayer(sourcePlayer);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(REQUEST_MAGIC);
            out.writeShort(PROTOCOL_VERSION);
            out.writeLong(matchId);
            out.writeByte(sourcePlayer);
            out.writeByte(wireType(event.kind()));
            writeEvent(out, event);
            out.flush();
            return appendChecksum(bytes, out);
        } catch (IOException impossible) {
            throw new IllegalStateException("memory request codec failed", impossible);
        }
    }

    public static DecodedRequest decodeRequest(byte[] packet) {
        if (packet == null || packet.length < 22
                || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid duel request length");
        }
        verifyChecksum(packet);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                packet, 0, packet.length - Integer.BYTES));
            if (in.readInt() != REQUEST_MAGIC) {
                throw new IllegalArgumentException("not a duel request");
            }
            int version = in.readUnsignedShort();
            if (version != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                    "unsupported duel protocol " + version);
            }
            long matchId = in.readLong();
            if (matchId == 0) {
                throw new IllegalArgumentException("zero request match id");
            }
            int sourcePlayer = in.readUnsignedByte();
            DuelEvent event = readEvent(in, in.readUnsignedByte());
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing duel request bytes");
            }
            return new DecodedRequest(matchId, sourcePlayer, event);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated duel request", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid duel request", failed);
        }
    }

    private static void writeEvent(DataOutputStream out, DuelEvent event)
            throws IOException {
        switch (event.kind()) {
            case RULES_PROPOSE: {
                DuelEvent.RulesPropose proposal =
                    (DuelEvent.RulesPropose) event;
                out.writeByte(proposal.player);
                out.writeInt(proposal.creditBudget);
                out.writeInt(proposal.buildSeconds);
                out.writeInt(proposal.matchSeconds);
                break;
            }
            case RULES_SET: {
                DuelEvent.RulesSet rules = (DuelEvent.RulesSet) event;
                out.writeLong(rules.revision);
                out.writeByte(rules.editorPlayer);
                out.writeInt(rules.creditBudget);
                out.writeInt(rules.buildSeconds);
                out.writeInt(rules.matchSeconds);
                break;
            }
            case BUILD_COMMIT: {
                DuelEvent.BuildCommit build = (DuelEvent.BuildCommit) event;
                out.writeByte(build.player);
                writeFort(out, build.design());
                break;
            }
            case READY_SET: {
                DuelEvent.ReadySet ready = (DuelEvent.ReadySet) event;
                out.writeByte(ready.player);
                out.writeBoolean(ready.ready);
                break;
            }
            case PHASE_CLOCK: {
                DuelEvent.PhaseClock clock = (DuelEvent.PhaseClock) event;
                out.writeInt(clock.round);
                out.writeByte(clock.phase.ordinal());
                out.writeLong(clock.startTick);
                out.writeLong(clock.deadlineTick);
                out.writeByte(clock.inputDelay);
                out.writeLong(clock.hostDeadlineMillis);
                break;
            }
            case ORDER: {
                DuelEvent.OrderIssued issued = (DuelEvent.OrderIssued) event;
                out.writeByte(issued.player);
                out.writeLong(issued.tick);
                Order order = issued.order();
                out.writeByte(order.kind.ordinal());
                out.writeInt(order.a);
                out.writeInt(order.b);
                break;
            }
            case FIRE: {
                DuelEvent.Fire fire = (DuelEvent.Fire) event;
                out.writeByte(fire.attacker);
                out.writeLong(fire.tick);
                out.writeLong(fire.shotId);
                out.writeInt(fire.weaponId);
                out.writeInt(fire.ordnanceType);
                out.writeFloat(fire.aimX);
                out.writeFloat(fire.aimY);
                break;
            }
            case IMPACT: {
                DuelEvent.Impact impact = (DuelEvent.Impact) event;
                out.writeByte(impact.defender);
                out.writeLong(impact.tick);
                out.writeLong(impact.shotId);
                out.writeInt(impact.roomId);
                out.writeFloat(impact.damage);
                DuelEvent.ImpactEffect[] effects = impact.effects();
                if (effects.length > MAX_EFFECTS) {
                    throw new IllegalArgumentException("too many impact effects");
                }
                out.writeByte(effects.length);
                for (DuelEvent.ImpactEffect effect : effects) {
                    out.writeInt(effect.type);
                    out.writeFloat(effect.scale);
                    out.writeFloat(effect.fire);
                    out.writeFloat(effect.hazard);
                    out.writeFloat(effect.crew);
                    out.writeInt(effect.status);
                    out.writeInt(effect.statusTicks);
                }
                break;
            }
            case FORT_SYNC: {
                DuelEvent.FortSync sync = (DuelEvent.FortSync) event;
                out.writeByte(sync.player);
                out.writeLong(sync.tick);
                out.writeShort(sync.size());
                for (int i = 0; i < sync.size(); i++) {
                    out.writeInt(sync.roomId(i));
                    out.writeFloat(sync.hitPoints(i));
                    out.writeInt(sync.state(i));
                }
                break;
            }
            case DEFEATED: {
                DuelEvent.Defeated defeated = (DuelEvent.Defeated) event;
                out.writeByte(defeated.player);
                out.writeLong(defeated.tick);
                break;
            }
            case REMATCH_READY: {
                DuelEvent.RematchReady rematch = (DuelEvent.RematchReady) event;
                out.writeByte(rematch.player);
                out.writeBoolean(rematch.ready);
                break;
            }
            case LEAVE: {
                DuelEvent.Leave leave = (DuelEvent.Leave) event;
                out.writeByte(leave.player);
                writeString(out, leave.reason, MAX_REASON_BYTES);
                break;
            }
            case ROUND_RESULT: {
                DuelEvent.RoundResult result =
                    (DuelEvent.RoundResult) event;
                out.writeByte(result.winner);
                out.writeByte(result.ending.ordinal());
                out.writeFloat(result.seconds);
                break;
            }
            default:
                throw new IllegalArgumentException("unsupported event " + event.kind());
        }
    }

    private static DuelEvent readEvent(DataInputStream in, int type)
            throws IOException {
        switch (type) {
            case 0:
                return new DuelEvent.RulesPropose(
                    in.readUnsignedByte(), in.readInt(),
                    in.readInt(), in.readInt());
            case 1:
                return new DuelEvent.RulesSet(
                    in.readLong(), in.readUnsignedByte(), in.readInt(),
                    in.readInt(), in.readInt());
            case 2:
                return new DuelEvent.BuildCommit(in.readUnsignedByte(), readFort(in));
            case 3:
                return new DuelEvent.ReadySet(
                    in.readUnsignedByte(), in.readBoolean());
            case 4: {
                int round = in.readInt();
                DuelEvent.ClockPhase phase = enumAt(
                    DuelEvent.ClockPhase.values(), in.readUnsignedByte(), "clock phase");
                return new DuelEvent.PhaseClock(
                    round, phase, in.readLong(), in.readLong(),
                    in.readUnsignedByte(), in.readLong());
            }
            case 5: {
                int player = in.readUnsignedByte();
                long tick = in.readLong();
                Order.Kind kind = enumAt(
                    Order.Kind.values(), in.readUnsignedByte(), "order kind");
                return new DuelEvent.OrderIssued(
                    player, tick, readOrder(kind, in.readInt(), in.readInt()));
            }
            case 6:
                return new DuelEvent.Fire(
                    in.readUnsignedByte(), in.readLong(), in.readLong(),
                    in.readInt(), in.readInt(), in.readFloat(), in.readFloat());
            case 7: {
                int defender = in.readUnsignedByte();
                long tick = in.readLong();
                long shotId = in.readLong();
                int roomId = in.readInt();
                float damage = in.readFloat();
                int count = in.readUnsignedByte();
                if (count > MAX_EFFECTS) {
                    throw new IllegalArgumentException("too many impact effects");
                }
                DuelEvent.ImpactEffect[] effects =
                    new DuelEvent.ImpactEffect[count];
                for (int i = 0; i < count; i++) {
                    effects[i] = new DuelEvent.ImpactEffect(
                        in.readInt(), in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readInt(), in.readInt());
                }
                return new DuelEvent.Impact(
                    defender, tick, shotId, roomId, damage, effects);
            }
            case 8: {
                int player = in.readUnsignedByte();
                long tick = in.readLong();
                int count = in.readUnsignedShort();
                if (count > MAX_ARRAY_ITEMS) {
                    throw new IllegalArgumentException("too many correction rooms");
                }
                int[] roomIds = new int[count];
                float[] hitPoints = new float[count];
                int[] states = new int[count];
                for (int i = 0; i < count; i++) {
                    roomIds[i] = in.readInt();
                    hitPoints[i] = in.readFloat();
                    states[i] = in.readInt();
                }
                return new DuelEvent.FortSync(
                    player, tick, roomIds, hitPoints, states);
            }
            case 9:
                return new DuelEvent.Defeated(
                    in.readUnsignedByte(), in.readLong());
            case 10:
                return new DuelEvent.RematchReady(
                    in.readUnsignedByte(), in.readBoolean());
            case 11:
                return new DuelEvent.Leave(
                    in.readUnsignedByte(), readString(in, MAX_REASON_BYTES));
            case 12:
                return new DuelEvent.RoundResult(
                    in.readByte(),
                    enumAt(redcon.duel.DuelMatch.Ending.values(),
                           in.readUnsignedByte(), "round ending"),
                    in.readFloat());
            default:
                throw new IllegalArgumentException("unknown duel event type " + type);
        }
    }

    private static int wireType(DuelEvent.Kind kind) {
        switch (kind) {
            case RULES_PROPOSE: return 0;
            case RULES_SET: return 1;
            case BUILD_COMMIT: return 2;
            case READY_SET: return 3;
            case PHASE_CLOCK: return 4;
            case ORDER: return 5;
            case FIRE: return 6;
            case IMPACT: return 7;
            case FORT_SYNC: return 8;
            case DEFEATED: return 9;
            case REMATCH_READY: return 10;
            case LEAVE: return 11;
            case ROUND_RESULT: return 12;
            default:
                throw new IllegalArgumentException(
                    "event is not part of committed protocol v3: " + kind);
        }
    }

    private static void writeFort(DataOutputStream out, FortDesign design)
            throws IOException {
        int blueprintId = design.blueprintId();
        if (blueprintId < 0 || f.c == null || blueprintId >= f.c.length
                || !(f.c[blueprintId] instanceof z0)) {
            throw new IllegalArgumentException(
                "fort has no authored blueprint id: " + blueprintId);
        }
        z0 fort = design.instantiate();
        validateFort(fort);
        out.writeInt(blueprintId);
        writeInts(out, fort.A);
        out.writeInt(fort.n);
        writeShorts(out, fort.o);
        out.writeInt(fort.p);
        writeShorts(out, fort.q);
        writeShorts(out, fort.r);
        out.writeInt(fort.s);
        out.writeInt(fort.t);
        out.writeInt(fort.u);
        out.writeInt(fort.v);
        writeBytes(out, fort.w);
        out.writeInt(fort.x);
        out.writeInt(fort.y);
        out.writeFloat(fort.z);
    }

    private static FortDesign readFort(DataInputStream in) throws IOException {
        int blueprintId = in.readInt();
        if (blueprintId < 0 || f.c == null || blueprintId >= f.c.length
                || !(f.c[blueprintId] instanceof z0)) {
            throw new IllegalArgumentException(
                "unknown fort blueprint id " + blueprintId);
        }
        FortDesign base = FortDesign.capture((z0) f.c[blueprintId]);
        z0 fort = base.instantiate();
        fort.A = readInts(in);
        fort.n = in.readInt();
        fort.o = readShorts(in);
        fort.p = in.readInt();
        fort.q = readShorts(in);
        short[] perks = readShorts(in);
        if (perks == null || perks.length != fort.r.length) {
            throw new IllegalArgumentException("invalid fort perk array");
        }
        System.arraycopy(perks, 0, fort.r, 0, fort.r.length);
        fort.s = in.readInt();
        fort.t = in.readInt();
        fort.u = in.readInt();
        fort.v = in.readInt();
        fort.w = readBytes(in);
        fort.x = in.readInt();
        fort.y = in.readInt();
        fort.z = in.readFloat();
        validateFort(fort);
        return base.captureEdited(fort);
    }

    private static void validateFort(z0 fort) {
        if (fort.n < 0 || fort.n > MAX_ARRAY_ITEMS / 5
                || fort.p < 0 || fort.p > MAX_ARRAY_ITEMS / 2
                || fort.o == null || fort.q == null
                || fort.o.length < fort.n * 5 || fort.q.length < fort.p * 2
                || !Float.isFinite(fort.z)) {
            throw new IllegalArgumentException("invalid fort layout payload");
        }
    }

    private static void writeInts(DataOutputStream out, int[] values)
            throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        checkArrayLength(values.length);
        out.writeInt(values.length);
        for (int value : values) {
            out.writeInt(value);
        }
    }

    private static int[] readInts(DataInputStream in) throws IOException {
        int length = readArrayLength(in);
        if (length < 0) {
            return null;
        }
        int[] result = new int[length];
        for (int i = 0; i < length; i++) {
            result[i] = in.readInt();
        }
        return result;
    }

    private static void writeShorts(DataOutputStream out, short[] values)
            throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        checkArrayLength(values.length);
        out.writeInt(values.length);
        for (short value : values) {
            out.writeShort(value);
        }
    }

    private static short[] readShorts(DataInputStream in) throws IOException {
        int length = readArrayLength(in);
        if (length < 0) {
            return null;
        }
        short[] result = new short[length];
        for (int i = 0; i < length; i++) {
            result[i] = in.readShort();
        }
        return result;
    }

    private static void writeBytes(DataOutputStream out, byte[] values)
            throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        checkArrayLength(values.length);
        out.writeInt(values.length);
        out.write(values);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = readArrayLength(in);
        if (length < 0) {
            return null;
        }
        byte[] result = new byte[length];
        in.readFully(result);
        return result;
    }

    private static void writeString(DataOutputStream out, String value, int max)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) {
            throw new IllegalArgumentException("string exceeds protocol limit");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int max)
            throws IOException {
        int length = in.readUnsignedShort();
        if (length > max) {
            throw new IllegalArgumentException("string exceeds protocol limit");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // Shared by reliable events and the live lockstep transport. Keep one
    // command vocabulary so a newly hooked control cannot disappear on UDP.
    static Order readOrder(Order.Kind kind, int a, int b) {
        switch (kind) {
            case SELECT_ROOM: return Order.selectRoom(a);
            case SELECT_ENEMY_ROOM: return Order.selectEnemyRoom(a);
            case CLEAR_SELECTION: return Order.clearSelection();
            case AIM: return Order.aim(a, b);
            case SELECT_CREW: return Order.selectCrew(a);
            case MOVE_CREW: return Order.moveCrew(a, b);
            case MOVE_CREW_TO_ENEMY_ROOM: return Order.moveCrewToEnemyRoom(a, b);
            case MOVE_SELECTED_CREW: return Order.moveSelectedCrew(a);
            case SET_ROOM_POWER:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid room power value " + b);
                return Order.setRoomPower(a, b == 1);
            case SET_ROOM_FIRE_MODE:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid fire mode value " + b);
                return Order.setRoomFireMode(a, b == 1);
            case SET_MANUAL_FIRE:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid manual fire value " + b);
                return Order.setManualFire(a, b == 1);
            case SET_AMMO:
                if (b < 0) throw new IllegalArgumentException("invalid payload index " + b);
                return Order.setAmmo(a, b);
            case SET_WEAPON_MODE:
                if (b < 0) throw new IllegalArgumentException("invalid weapon mode " + b);
                return Order.setWeaponMode(a, b);
            case AIRSHIP_ACTION: return Order.airshipAction();
            case SET_AIRSHIP_CARGO:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid airship cargo value " + b);
                return Order.setAirshipCargo(b == 1);
            case SET_AIRSHIP_TARGET:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid airship target fort " + b);
                return Order.setAirshipTarget(a, b == 1);
            case FORCE_FIRE: return Order.forceFire(a);
            case CLANDESTINE_CONVERT: return Order.clandestineConvert();
            case BOARD_AIRSHIP:
                if (b != 0 && b != 1) throw new IllegalArgumentException("invalid airship owner " + b);
                return Order.boardAirship(a, b == 1);
            default:
                throw new IllegalArgumentException("unsupported order " + kind);
        }
    }

    private static <T> T enumAt(T[] values, int index, String name) {
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("unknown " + name + " " + index);
        }
        return values[index];
    }

    private static int readArrayLength(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < -1 || length > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException("invalid protocol array length " + length);
        }
        return length;
    }

    private static void checkArrayLength(int length) {
        if (length > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException("array exceeds protocol limit");
        }
    }

    private static void verifyChecksum(byte[] packet) {
        int bodyLength = packet.length - Integer.BYTES;
        int expected = ((packet[bodyLength] & 0xff) << 24)
                     | ((packet[bodyLength + 1] & 0xff) << 16)
                     | ((packet[bodyLength + 2] & 0xff) << 8)
                     | (packet[bodyLength + 3] & 0xff);
        CRC32 crc = new CRC32();
        crc.update(packet, 0, bodyLength);
        if ((int) crc.getValue() != expected) {
            throw new IllegalArgumentException("duel packet checksum mismatch");
        }
    }

    private static byte[] appendChecksum(ByteArrayOutputStream bytes,
                                         DataOutputStream out)
            throws IOException {
        if (bytes.size() + Integer.BYTES > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("duel packet exceeds packet limit");
        }
        byte[] body = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        out.writeInt((int) crc.getValue());
        out.flush();
        return bytes.toByteArray();
    }

    private DuelPacketCodec() {
    }
}
