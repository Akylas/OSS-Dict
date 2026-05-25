package itkach.aard2.audio;

import androidx.annotation.NonNull;

import org.xiph.speex.SpeexDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes an Ogg-encapsulated Speex (.spx) stream to signed 16-bit PCM samples.
 *
 * <p>Handles the Ogg page/packet framing, reads the Speex stream header to
 * configure the decoder, skips the comment header, then decodes all audio
 * packets using the pure-Java jspeex {@link SpeexDecoder}.</p>
 */
public class OggSpeexDecoder {

    private final byte[] buf;
    private int pos;

    private int sampleRate = 8000;
    private int channels   = 1;

    public OggSpeexDecoder(@NonNull byte[] data) {
        this.buf = data;
        this.pos = 0;
    }

    /** Sample rate reported in the Speex stream header (valid after {@link #decode()}). */
    public int getSampleRate() { return sampleRate; }

    /** Channel count reported in the Speex stream header (valid after {@link #decode()}). */
    public int getChannels()   { return channels; }

    /**
     * Decodes the full Ogg-Speex stream.
     *
     * @return interleaved signed 16-bit PCM samples
     * @throws IOException on malformed or unsupported streams
     */
    @NonNull
    public short[] decode() throws IOException {
        List<byte[]> packets = readAllPackets();
        if (packets.size() < 2) {
            throw new StreamCorruptedException("Speex stream has fewer than 2 packets");
        }

        // Packet 0: Speex stream header
        byte[] hdrPkt = packets.get(0);
        if (!hasSpeexMagic(hdrPkt)) {
            throw new StreamCorruptedException("Not a Speex stream (missing magic)");
        }
        if (hdrPkt.length < 68) {
            throw new StreamCorruptedException("Speex header too short: " + hdrPkt.length);
        }
        ByteBuffer hdr = ByteBuffer.wrap(hdrPkt).order(ByteOrder.LITTLE_ENDIAN);
        hdr.position(36);
        sampleRate          = hdr.getInt(); // offset 36
        int mode            = hdr.getInt(); // offset 40  (0=NB, 1=WB, 2=UWB)
        hdr.position(48);
        channels            = hdr.getInt(); // offset 48
        hdr.position(64);
        int framesPerPacket = hdr.getInt(); // offset 64
        if (framesPerPacket <= 0) framesPerPacket = 1;

        // Packet 1: comment header – skip

        // Initialise decoder
        SpeexDecoder decoder = new SpeexDecoder();
        if (!decoder.init(mode, sampleRate, channels, true)) {
            throw new IOException("Failed to init SpeexDecoder for mode=" + mode);
        }

        // Decode audio packets (indices 2 … n-1)
        List<short[]> pcmChunks = new ArrayList<>();
        int totalSamples = 0;
        for (int i = 2; i < packets.size(); i++) {
            byte[] pkt = packets.get(i);
            if (pkt.length == 0) continue;
            // For CBR each frame occupies an equal slice of the packet
            int frameBytes = pkt.length / framesPerPacket;
            if (frameBytes == 0) continue;
            for (int f = 0; f < framesPerPacket; f++) {
                try {
                    decoder.processData(pkt, f * frameBytes, frameBytes);
                    int outBytes = decoder.getProcessedDataByteSize();
                    if (outBytes <= 0) continue;
                    short[] pcm = new short[outBytes / 2];
                    decoder.getProcessedData(pcm, 0);
                    pcmChunks.add(pcm);
                    totalSamples += pcm.length;
                } catch (Exception ignored) {
                    // Skip corrupted frames rather than aborting the whole clip
                }
            }
        }

        // Merge all chunks into one array
        short[] result = new short[totalSamples];
        int dst = 0;
        for (short[] chunk : pcmChunks) {
            System.arraycopy(chunk, 0, result, dst, chunk.length);
            dst += chunk.length;
        }
        return result;
    }

    // ── OGG container parsing ────────────────────────────────────────────────

    /**
     * Reads every logical Ogg packet from the buffer.
     *
     * <p>Handles packets that span multiple pages (lacing with 255-byte segments).</p>
     */
    @NonNull
    private List<byte[]> readAllPackets() {
        List<byte[]> packets = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();

        while (pos < buf.length) {
            if (!seekToOggPage()) break;

            // OGG page layout (from RFC 3533):
            //   0- 3  "OggS" capture pattern
            //   4     stream_structure_version
            //   5     header_type_flag (bit 0 = continued page)
            //   6-13  absolute granule position
            //  14-17  stream serial number
            //  18-21  page sequence number
            //  22-25  CRC checksum
            //  26     number_page_segments
            //  27…   segment_table[number_page_segments]
            if (pos + 27 > buf.length) break;

            int nSegs = buf[pos + 26] & 0xff;
            pos += 27;

            if (pos + nSegs > buf.length) break;
            int[] segSizes  = new int[nSegs];
            int pageDataLen = 0;
            for (int i = 0; i < nSegs; i++) {
                segSizes[i] = buf[pos++] & 0xff;
                pageDataLen += segSizes[i];
            }
            if (pos + pageDataLen > buf.length) break;

            for (int i = 0; i < nSegs; i++) {
                current.write(buf, pos, segSizes[i]);
                pos += segSizes[i];
                if (segSizes[i] < 255) {
                    // Segment < 255 bytes terminates the current packet
                    packets.add(current.toByteArray());
                    current.reset();
                }
                // Segment == 255 bytes means the packet continues in the next segment/page
            }
        }

        // Flush any unterminated packet at EOF
        if (current.size() > 0) {
            packets.add(current.toByteArray());
        }
        return packets;
    }

    /** Advances {@link #pos} to the next {@code OggS} capture pattern. */
    private boolean seekToOggPage() {
        while (pos + 4 <= buf.length) {
            if (buf[pos]   == 'O' && buf[pos+1] == 'g' &&
                buf[pos+2] == 'g' && buf[pos+3] == 'S') {
                return true;
            }
            pos++;
        }
        return false;
    }

    private static boolean hasSpeexMagic(@NonNull byte[] pkt) {
        if (pkt.length < 8) return false;
        return pkt[0] == 'S' && pkt[1] == 'p' && pkt[2] == 'e' && pkt[3] == 'e' &&
               pkt[4] == 'x' && pkt[5] == ' ' && pkt[6] == ' ' && pkt[7] == ' ';
    }
}
