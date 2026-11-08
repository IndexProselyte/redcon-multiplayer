package redcon.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import mojo.e0;

/**
 * Brings REDCON's recovered code up far enough to deserialise its content.
 *
 * This is the loader from m3.b case 6, minus the Android startup around it.
 * Three pieces of state have to be planted by hand first, each something
 * Platform would normally set: the language code, the factory instance, and
 * m3.b's static registries.
 *
 * Run against the enjarify jar with verification disabled - dex permits a few
 * things the JVM verifier does not, such as calling a superclass constructor
 * directly on a fresh instance:
 *
 * <pre>
 * java -XX:+UnlockDiagnosticVMOptions
 *      -XX:-BytecodeVerificationRemote -XX:-BytecodeVerificationLocal ...
 * </pre>
 */
public final class RecoveredGame {

    public static final String DEFAULT_PACK =
        "media/rs.pk";

    private RecoveredGame() {
    }

    /** Deserialise res/game and return the global object table. */
    public static mojo.f[] loadContent(Path pack) throws Exception {
        return load(findGameChunk(pack));
    }

    private static mojo.f[] load(byte[] game) throws Exception {
        c1.a.z = "en";

        e0 reader = new e0(game, 0, game.length);
        int count = reader.u();
        mojo.f[] objects = new mojo.f[count];

        m3.b factory = allocate(m3.b.class);
        mojo.b.i = factory;
        // Allocating an instance does not run the class initialiser, and the
        // perk and fort readers use m3.b's static registries. Assigning a
        // static field forces <clinit>.
        m3.b.E = factory;

        for (int i = 0; i < count; i++) {
            objects[i] = factory.j(reader.n());
        }

        reader.f = 0;
        reader.g = reader.l();
        reader.i = objects;
        reader.e = allocate(mojo.g.class);
        for (int i = 0; i < count; i++) {
            reader.h = i;
            objects[i].readDatum(reader);
        }
        reader.i = null;
        reader.e = null;
        reader.g = null;
        mojo.f.c = objects;
        return objects;
    }

    /**
     * Build an instance without running a constructor. m3.b is the Android
     * application class, so its constructor is not safe here, and several mojo
     * classes have no reachable one. The loader needs these only as containers.
     */
    @SuppressWarnings("unchecked")
    public static <T> T allocate(Class<T> type) throws Exception {
        try {
            java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            java.lang.reflect.Field unsafeField =
                Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            java.lang.reflect.Method allocateInstance = unsafe.getClass()
                .getMethod("allocateInstance", Class.class);
            return (T) allocateInstance.invoke(unsafe, type);
        }
    }

    /**
     * Fill in the tables the simulation reaches for when it wants a sound.
     *
     * Damage is computed first and announced second - v1.E updates structure on
     * its opening line, then calls E0 to play a hit or destruction cue. E0
     * indexes c1.a.l, the sound bank, which only exists once audio has loaded.
     * Stubbing it lets the whole method run headlessly instead of throwing
     * after the arithmetic is already done.
     */
    public static void stubSoundBank(int size) throws Exception {
        k3.b[] bank = new k3.b[size];
        for (int i = 0; i < bank.length; i++) {
            bank[i] = allocate(k3.b.class);
        }
        c1.a.l = bank;
    }

    public static byte[] findGameChunk(Path pack) throws Exception {
        byte[] needle = "fort_setup#".getBytes(StandardCharsets.ISO_8859_1);
        for (byte[] chunk : HexagePack.readChunks(pack)) {
            outer:
            for (int i = 0; i + needle.length <= chunk.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (chunk[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return chunk;
            }
        }
        throw new IllegalStateException("no chunk in " + pack + " contains fort_setup");
    }
}
