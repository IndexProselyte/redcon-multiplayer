package redcon.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for Hexage's container format, as shipped in {@code media/*.pk}.
 *
 * <pre>
 * ["\xA9 HEXAGE"]              8-byte signature; on *.pk, absent on `mf`
 * repeated chunk:
 *     bytes  EF 77 BE 36
 *     u32 le uncompressed_size XOR 0x30ED45A2
 *     bytes  zlib stream
 * </pre>
 *
 * There is no checksum anywhere in the format.
 */
public final class HexagePack {

    private static final byte[] SIGNATURE = {
        (byte) 0xA9, ' ', 'H', 'E', 'X', 'A', 'G', 'E'
    };
    private static final byte[] MAGIC = {
        (byte) 0xEF, (byte) 0x77, (byte) 0xBE, (byte) 0x36
    };
    private static final int XOR_KEY = 0x30ED45A2;

    private HexagePack() {
    }

    public static List<byte[]> readChunks(Path file) throws IOException {
        byte[] blob = Files.readAllBytes(file);
        int offset = startsWith(blob, 0, SIGNATURE) ? SIGNATURE.length : 0;
        List<byte[]> chunks = new ArrayList<>();
        while (offset + 8 <= blob.length) {
            if (!startsWith(blob, offset, MAGIC)) {
                offset++;
                continue;
            }
            int declared = readIntLE(blob, offset + 4) ^ XOR_KEY;
            Inflater inflater = new Inflater();
            inflater.setInput(blob, offset + 8, blob.length - offset - 8);
            byte[] raw = new byte[declared];
            try {
                int written = inflater.inflate(raw);
                if (written != declared) {
                    throw new IOException("chunk at " + offset + ": header declared "
                                          + declared + " bytes, zlib produced " + written);
                }
            } catch (DataFormatException e) {
                throw new IOException("chunk at " + offset + " is not zlib", e);
            }
            offset += 8 + (int) inflater.getBytesRead();
            inflater.end();
            chunks.add(raw);
        }
        return chunks;
    }

    private static boolean startsWith(byte[] blob, int at, byte[] want) {
        if (at + want.length > blob.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (blob[at + i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readIntLE(byte[] blob, int at) {
        return (blob[at] & 0xFF)
             | ((blob[at + 1] & 0xFF) << 8)
             | ((blob[at + 2] & 0xFF) << 16)
             | ((blob[at + 3] & 0xFF) << 24);
    }
}
