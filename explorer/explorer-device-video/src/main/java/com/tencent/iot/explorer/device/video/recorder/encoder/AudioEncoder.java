package com.tencent.iot.explorer.device.video.recorder.encoder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.os.Looper;
import android.util.Log;

import com.tencent.iot.explorer.device.video.recorder.listener.OnEncodeListener;
import com.tencent.iot.explorer.device.video.recorder.listener.OnReadAECProcessedPcmListener;
import com.tencent.iot.explorer.device.video.recorder.param.AudioEncodeParam;
import com.tencent.iot.explorer.device.video.recorder.param.MicParam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.tencent.iot.explorer.device.android.utils.ConvertUtils.byte2HexOnlyLatest8;
import static com.tencent.iot.explorer.device.video.recorder.consts.LogConst.RTC_TAG;

public class AudioEncoder {

    /**
     * 采样频率对照表
     */
    private static final Map<Integer, Integer> samplingFrequencyIndexMap = new HashMap<>();

    static {
        samplingFrequencyIndexMap.put(96000, 0);
        samplingFrequencyIndexMap.put(88200, 1);
        samplingFrequencyIndexMap.put(64000, 2);
        samplingFrequencyIndexMap.put(48000, 3);
        samplingFrequencyIndexMap.put(44100, 4);
        samplingFrequencyIndexMap.put(32000, 5);
        samplingFrequencyIndexMap.put(24000, 6);
        samplingFrequencyIndexMap.put(22050, 7);
        samplingFrequencyIndexMap.put(16000, 8);
        samplingFrequencyIndexMap.put(12000, 9);
        samplingFrequencyIndexMap.put(11025, 10);
        samplingFrequencyIndexMap.put(8000, 11);
    }

    private final String TAG = AudioEncoder.class.getSimpleName();
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler canceler;
    private AutomaticGainControl control;

    private final MicParam micParam;
    private final AudioEncodeParam audioEncodeParam;
    private OnEncodeListener encodeListener;

    private volatile boolean stopEncode = false;
    private long seq = 0L;
    private long beforSeq = 0L;
    private int bufferSizeInBytes;

    private OnReadAECProcessedPcmListener mAECProcessedPcmListener;

    public AudioEncoder(MicParam micParam, AudioEncodeParam audioEncodeParam) {
        this(micParam, audioEncodeParam, false, false);
    }

    public AudioEncoder(MicParam micParam, AudioEncodeParam audioEncodeParam, OnReadAECProcessedPcmListener listener) {
        this(micParam, audioEncodeParam, false, false);
        this.mAECProcessedPcmListener = listener;
    }

    public AudioEncoder(MicParam micParam, AudioEncodeParam audioEncodeParam, boolean enableAEC, boolean enableAGC) {
        this.micParam = micParam;
        this.audioEncodeParam = audioEncodeParam;
        initAudio();
        int audioSessionId = audioRecord.getAudioSessionId();
        if (enableAEC && audioSessionId != 0) {
            Log.e(TAG, "=====initAEC result: " + initAEC(audioSessionId));
        }
        if (enableAGC && audioSessionId != 0) {
            Log.e(TAG, "=====initAGC result: " + initAGC(audioSessionId));
        }
    }

    public void setOnEncodeListener(OnEncodeListener listener) {
        this.encodeListener = listener;
    }

    private void initAudio() {
        bufferSizeInBytes = AudioRecord.getMinBufferSize(micParam.getSampleRateInHz(), micParam.getChannelConfig(), micParam.getAudioFormat());
        Log.e(TAG, "=====bufferSizeInBytes: " + bufferSizeInBytes);
        audioRecord = new AudioRecord(micParam.getAudioSource(), micParam.getSampleRateInHz(), micParam.getChannelConfig(), micParam.getAudioFormat(), bufferSizeInBytes);
        try {
            audioCodec = MediaCodec.createEncoderByType(audioEncodeParam.getMime());
            Log.i(RTC_TAG, "audioCodec MediaCodec createEncoderByType");
            MediaFormat format = MediaFormat.createAudioFormat(audioEncodeParam.getMime(), micParam.getSampleRateInHz(), 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, audioEncodeParam.getBitRate());
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioEncodeParam.getMaxInputSize());
            audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IOException e) {
            e.printStackTrace();
            audioRecord = null;
            audioCodec = null;
        }
    }

    public void start() {
        new CodecThread().start();
    }

    public void stop() {
        stopEncode = true;
    }

    public boolean isDevicesSupportAEC() {
        return AcousticEchoCanceler.isAvailable();
    }

    private boolean initAEC(int audioSession) {

        boolean isDevicesSupportAEC = isDevicesSupportAEC();
        Log.e(TAG, "isDevicesSupportAEC: "+isDevicesSupportAEC);
        if (!isDevicesSupportAEC) {
            return false;
        }
        if (canceler != null) {
            return false;
        }
        canceler = AcousticEchoCanceler.create(audioSession);
        canceler.setEnabled(true);
        return canceler.getEnabled();
    }

    public boolean isDevicesSupportAGC() {
        return AutomaticGainControl.isAvailable();
    }

    private boolean initAGC(int audioSession) {

        boolean isDevicesSupportAGC = isDevicesSupportAGC();
        Log.e(TAG, "isDevicesSupportAGC: "+isDevicesSupportAGC);
        if (!isDevicesSupportAGC) {
            return false;
        }
        if (control != null) {
            return false;
        }
        control = AutomaticGainControl.create(audioSession);
        control.setEnabled(true);
        return control.getEnabled();
    }

    private void release() {
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (audioCodec != null) {
            audioCodec.stop();
            audioCodec.release();
            audioCodec = null;
        }

        if (canceler != null) {
            canceler.setEnabled(false);
            canceler.release();
            canceler = null;
        }

        if (control != null) {
            control.setEnabled(false);
            control.release();
            control = null;
        }
        if (mAECProcessedPcmListener != null) {
            mAECProcessedPcmListener.audioCodecRelease();
        }
    }

    private void addADTStoPacket(ByteBuffer outputBuffer) {
        byte[] bytes = new byte[outputBuffer.remaining()];
        outputBuffer.get(bytes, 0, bytes.length);
        byte[] dataBytes = new byte[bytes.length + 7];
        System.arraycopy(bytes, 0, dataBytes, 7, bytes.length);
        addADTStoPacket(dataBytes, dataBytes.length);
        if (stopEncode) {
            return;
        }
        if (encodeListener != null) {
            Log.i(RTC_TAG, "on audio encoded byte: "+byte2HexOnlyLatest8(dataBytes) + "; seq: " + seq);
            encodeListener.onAudioEncoded(dataBytes, System.currentTimeMillis(), seq);
            seq++;
        } else {
            Log.e(TAG, "Encode listener is null, please set encode listener.");
        }
    }

    private void addADTStoPacket(byte[] packet, int packetLen) {
        // AAC LC
        int profile = 2;
        // CPE
        int chanCfg = 1;
        int freqIdx = samplingFrequencyIndexMap.get(micParam.getSampleRateInHz());
        // filled in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    class CodecThread extends Thread {
        @Override
        public void run() {
            super.run();
            Looper.prepare();
            if (audioCodec == null) {
                return;
            }
            stopEncode = false;
            audioRecord.startRecording();
            Log.i(RTC_TAG, "audioRecord startRecording");
            audioCodec.start();
            Log.i(RTC_TAG, String.format("audioCodec start with MediaFormat AudioSource: %d, SampleRateInHz: %d, IsChannelMono: %b, bitDepth: %d, encodeMime: %s, bitRate: %d, CodecProfileLevel: %d, KEY_MAX_INPUT_SIZE:%d",
                    micParam.getAudioSource(), micParam.getSampleRateInHz(), micParam.getChannelConfig()==AudioFormat.CHANNEL_IN_MONO, micParam.getAudioFormat(),
                    audioEncodeParam.getMime(), audioEncodeParam.getBitRate(), MediaCodecInfo.CodecProfileLevel.AACObjectLC, audioEncodeParam.getMaxInputSize()));
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
            while (true) {
                if (stopEncode) {
                    release();
                    break;
                }

                // 将 AudioRecord 获取的 PCM 原始数据送入编码器
                int audioInputBufferId = audioCodec.dequeueInputBuffer(0);
                if (audioInputBufferId >= 0) {
                    ByteBuffer inputBuffer = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        inputBuffer = audioCodec.getInputBuffer(audioInputBufferId);
                    } else {
                        inputBuffer = audioCodec.getInputBuffers()[audioInputBufferId];
                    }
                    int readSize = -1;
                    int size = mAECProcessedPcmListener != null ? 2560 : bufferSizeInBytes;
                    byte[] audioRecordData = new byte[size];
                    if (inputBuffer != null) {
                        readSize = audioRecord.read(audioRecordData, 0, size);
                    }
                    if (readSize >= 0) {
                        inputBuffer.clear();
                        if (mAECProcessedPcmListener != null) {
                            Log.i(RTC_TAG, String.format("audioRecord read capture original frame data before other process:%s, seq:%d", byte2HexOnlyLatest8(audioRecordData), beforSeq));
                            byte[] cancell = mAECProcessedPcmListener.onReadAECProcessedPcmListener(audioRecordData);
                            Log.i(RTC_TAG, String.format("audioRecord read capture original frame data after other process:%s, seq:%d", byte2HexOnlyLatest8(audioRecordData), beforSeq));
                            beforSeq++;
                            inputBuffer.put(cancell);
                        } else {
                            inputBuffer.put(audioRecordData);
                            Log.i(RTC_TAG, String.format("audioRecord read capture origina l frame data:%s", byte2HexOnlyLatest8(audioRecordData)));
                            Log.i("audioRecordTest", "---without cancel");
                        }
                        audioCodec.queueInputBuffer(audioInputBufferId, 0, readSize, System.nanoTime() / 1000, 0);
                    }
                }

                int audioOutputBufferId = audioCodec.dequeueOutputBuffer(audioInfo, 0);
                while (audioOutputBufferId >= 0) {
                    ByteBuffer outputBuffer = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        outputBuffer = audioCodec.getOutputBuffer(audioOutputBufferId);
                    } else {
                        outputBuffer = audioCodec.getOutputBuffers()[audioOutputBufferId];
                    }
                    if (audioInfo.size > 2) {
                        outputBuffer.position(audioInfo.offset);
                        outputBuffer.limit(audioInfo.offset + audioInfo.size);
                        Log.i(RTC_TAG, String.format("audioCodec getOutputBuffer audioInfo.offset :%d + audioInfo.size :%d", audioInfo.offset, audioInfo.size));
                        addADTStoPacket(outputBuffer);
                    }
                    audioCodec.releaseOutputBuffer(audioOutputBufferId, false);
                    audioOutputBufferId = audioCodec.dequeueOutputBuffer(audioInfo, 0);
                }
            }

            Looper.loop();
        }
    }
}
