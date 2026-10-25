package com.zhaowen.mapmate;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;

/** 录音 -> Opus 编码；Opus -> 播放。 */
public class AudioIO {

    private static final String TAG = "MapMateAudio";

    public static final int REC_RATE = 16000;
    public static final int PLAY_RATE = 24000;
    public static final int FRAME_MS = 60;
    private static final int REC_FRAME = REC_RATE * FRAME_MS / 1000;    // 960 samples
    private static final int PLAY_FRAME = PLAY_RATE * FRAME_MS / 1000;  // 1440 samples

    public interface FrameSink {
        void onOpusFrame(byte[] opus);
    }

    private AudioRecord record;
    private AudioTrack track;
    private OpusEncoder encoder;
    private OpusDecoder decoder;

    private Thread recThread;
    private volatile boolean recording;
    private volatile boolean released;

    public boolean init() {
        try {
            encoder = new OpusEncoder(REC_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24000);
            encoder.setComplexity(5);
            decoder = new OpusDecoder(PLAY_RATE, 1);

            int minRec = AudioRecord.getMinBufferSize(REC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, REC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minRec, REC_FRAME * 2 * 4));

            int minPlay = AudioTrack.getMinBufferSize(PLAY_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, PLAY_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minPlay, PLAY_FRAME * 2 * 4), AudioTrack.MODE_STREAM);
            track.play();
            Log.i(TAG, "audio init ok; recState=" + record.getState() + " trackState=" + track.getState());
            return record.getState() == AudioRecord.STATE_INITIALIZED
                    && track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (Throwable t) {
            Log.e(TAG, "audio init failed", t);
            return false;
        }
    }

    public void startRecording(final FrameSink sink) {
        if (recording || record == null) return;
        recording = true;
        record.startRecording();
        recThread = new Thread(() -> {
            short[] pcm = new short[REC_FRAME];
            byte[] out = new byte[4000];
            while (recording) {
                int n = record.read(pcm, 0, REC_FRAME);
                if (n <= 0) continue;
                try {
                    int len = encoder.encode(pcm, 0, REC_FRAME, out, 0, out.length);
                    if (len > 0) {
                        byte[] pkt = new byte[len];
                        System.arraycopy(out, 0, pkt, 0, len);
                        sink.onOpusFrame(pkt);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "encode failed", t);
                }
            }
        }, "opus-rec");
        recThread.start();
    }

    public void stopRecording() {
        recording = false;
        try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        if (recThread != null) {
            try { recThread.join(500); } catch (InterruptedException ignored) {}
            recThread = null;
        }
    }

    public void playOpus(byte[] data) {
        if (decoder == null || track == null) return;
        try {
            short[] pcm = new short[PLAY_FRAME];
            int n = decoder.decode(data, 0, data.length, pcm, 0, PLAY_FRAME, false);
            if (n > 0) track.write(pcm, 0, n);
        } catch (Throwable t) {
            Log.e(TAG, "decode failed len=" + data.length, t);
        }
    }

    public void flushPlayback() {
        try { if (track != null) track.pause(); track.flush(); track.play(); } catch (Throwable ignored) {}
    }

    public void release() {
        released = true;
        stopRecording();
        try { if (record != null) record.release(); } catch (Throwable ignored) {}
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
        record = null;
        track = null;
    }
}