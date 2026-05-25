package itkach.aard2.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plays dictionary audio referenced by a SlobServer URL.
 *
 * <ul>
 *   <li>Ogg-Speex ({@code .spx}) files are downloaded, software-decoded to PCM
 *       by {@link OggSpeexDecoder}, and rendered via {@link AudioTrack}.</li>
 *   <li>All other audio formats are handed directly to {@link MediaPlayer}
 *       (MP3, OGG Vorbis, WAV, etc.).</li>
 * </ul>
 *
 * <p>Only one audio clip plays at a time; starting a new clip automatically
 * stops the previous one.</p>
 */
public class DictAudioPlayer {

    private static final String TAG = DictAudioPlayer.class.getSimpleName();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Future<?>            currentTask;
    private volatile MediaPlayer mediaPlayer;
    private volatile AudioTrack  audioTrack;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts playing the audio at {@code url} (a localhost SlobServer URL).
     * Any previously playing audio is stopped first.
     */
    public synchronized void play(@NonNull String url) {
        cancelCurrent();
        currentTask = executor.submit(() -> doPlay(url));
    }

    /** Stops any currently playing audio. */
    public synchronized void stop() {
        cancelCurrent();
    }

    /**
     * Releases all resources. Must be called when this player is no longer
     * needed (e.g., from {@code View.onDetachedFromWindow} or
     * {@code WebView.destroy}).
     */
    public void release() {
        stop();
        executor.shutdown();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private synchronized void cancelCurrent() {
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        releaseMp();
        releaseAt();
    }

    private void doPlay(@NonNull String url) {
        try {
            if (url.toLowerCase().endsWith(".spx")) {
                playSpx(url);
            } else {
                playUrl(url);
            }
        } catch (InterruptedException ignored) {
            // Cancelled by stop() / play()
        } catch (Exception e) {
            Log.e(TAG, "Audio playback failed for " + url, e);
        }
    }

    // ── SPX (Ogg-Speex) playback via AudioTrack ───────────────────────────────

    private void playSpx(@NonNull String url) throws Exception {
        byte[] spxBytes = fetchBytes(url);
        if (Thread.interrupted()) return;

        OggSpeexDecoder oggDecoder = new OggSpeexDecoder(spxBytes);
        short[] pcm    = oggDecoder.decode();
        int sampleRate = oggDecoder.getSampleRate();
        int channels   = oggDecoder.getChannels();

        if (Thread.interrupted()) return;
        if (pcm.length == 0) return;

        int channelMask = channels > 1
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        synchronized (this) {
            if (Thread.interrupted()) { track.release(); return; }
            audioTrack = track;
        }

        track.write(pcm, 0, pcm.length);
        track.play();

        // Poll until playback finishes or we are cancelled
        while (track.getPlaybackHeadPosition() < pcm.length) {
            if (Thread.interrupted()) { track.stop(); break; }
            try { Thread.sleep(30); } catch (InterruptedException e) { track.stop(); break; }
        }

        track.release();
        synchronized (this) { if (audioTrack == track) audioTrack = null; }
    }

    // ── Standard format playback via MediaPlayer ──────────────────────────────

    private void playUrl(@NonNull String url) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        synchronized (this) { mediaPlayer = mp; }
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.prepare(); // blocking; fine on a background thread
            if (Thread.interrupted()) return;
            mp.start();

            while (mp.isPlaying()) {
                if (Thread.interrupted()) { mp.stop(); return; }
                try { Thread.sleep(50); } catch (InterruptedException e) { mp.stop(); return; }
            }
        } finally {
            mp.release();
            synchronized (this) { if (mediaPlayer == mp) mediaPlayer = null; }
        }
    }

    // ── Resource cleanup ──────────────────────────────────────────────────────

    private void releaseMp() {
        MediaPlayer mp = mediaPlayer;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            mp.release();
            mediaPlayer = null;
        }
    }

    private void releaseAt() {
        AudioTrack at = audioTrack;
        if (at != null) {
            try { at.stop(); } catch (Exception ignored) {}
            at.release();
            audioTrack = null;
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    @NonNull
    private static byte[] fetchBytes(@NonNull String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
