package itkach.aard2.dictionary.dsl;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * {@link DslResourceZip} on a hand-built archive whose only entry stores its
 * size, compressed size and offset in a ZIP64 extra field (all three 32-bit
 * fields set to 0xFFFFFFFF), as a writer does for entries past 4 GB.
 */
public class DslResourceZipExtraFieldTest {

    private static final byte[] DATA = "sound bytes".getBytes(StandardCharsets.UTF_8);
    private static final String NAME = "word.mp3";

    private File archive;
    private FileInputStream input;

    @After
    public void tearDown() throws IOException {
        if (input != null) input.close();
        if (archive != null) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test
    public void readsEntryWhoseValuesAreInZip64ExtraField() throws IOException {
        DslResourceZip zip = open(buildArchive(24));
        assertArrayEquals(DATA, zip.read(NAME));
    }

    @Test
    public void truncatedZip64ExtraFieldIsIoException() throws IOException {
        DslResourceZip zip = open(buildArchive(8));
        try {
            zip.read(NAME);
            fail("expected IOException");
        } catch (IOException expected) {
            // corrupt directory must not escape as an unchecked exception
        }
    }

    private DslResourceZip open(byte[] bytes) throws IOException {
        archive = File.createTempFile("extra", ".dsl.files.zip");
        try (FileOutputStream output = new FileOutputStream(archive)) {
            output.write(bytes);
        }
        input = new FileInputStream(archive);
        return new DslResourceZip(input.getChannel());
    }

    /** @param extraPayload length of the ZIP64 extra field data: 24 is complete, less is truncated */
    private static byte[] buildArchive(int extraPayload) {
        byte[] nameBytes = NAME.getBytes(StandardCharsets.UTF_8);
        int extraLength = 4 + extraPayload;
        int localSize = 30 + nameBytes.length + DATA.length;
        int directorySize = 46 + nameBytes.length + extraLength;
        int zip64RecordOffset = localSize + directorySize;
        ByteBuffer out = ByteBuffer.allocate(zip64RecordOffset + 56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN);

        out.putInt(0x04034b50).putShort((short) 45).putShort((short) 0).putShort((short) 0);
        out.putInt(0).putInt(0).putInt(0).putInt(0);
        out.putShort((short) nameBytes.length).putShort((short) 0);
        out.put(nameBytes).put(DATA);

        out.putInt(0x02014b50).putShort((short) 45).putShort((short) 45).putShort((short) 0).putShort((short) 0);
        out.putInt(0).putInt(0);
        out.putInt(0xFFFFFFFF).putInt(0xFFFFFFFF);
        out.putShort((short) nameBytes.length).putShort((short) extraLength).putShort((short) 0);
        out.putShort((short) 0).putShort((short) 0).putInt(0).putInt(0xFFFFFFFF);
        out.put(nameBytes);
        out.putShort((short) 1).putShort((short) extraPayload);
        if (extraPayload >= 8) out.putLong(DATA.length);
        if (extraPayload >= 16) out.putLong(DATA.length);
        if (extraPayload >= 24) out.putLong(0);

        out.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0);
        out.putLong(1).putLong(1).putLong(directorySize).putLong(localSize);
        out.putInt(0x07064b50).putInt(0).putLong(zip64RecordOffset).putInt(1);
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0);
        out.putShort((short) 0xFFFF).putShort((short) 0xFFFF).putInt(0xFFFFFFFF).putInt(0xFFFFFFFF);
        out.putShort((short) 0);
        return out.array();
    }
}
