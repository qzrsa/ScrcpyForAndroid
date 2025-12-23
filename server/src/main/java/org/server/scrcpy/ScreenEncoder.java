package org.server.scrcpy;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import org.server.scrcpy.audio.AudioCaptureException;
import org.server.scrcpy.model.MediaPacket;
import org.server.scrcpy.model.VideoPacket;
import org.server.scrcpy.wrappers.DisplayManager;
import org.server.scrcpy.wrappers.ServiceManager;
import org.server.scrcpy.wrappers.SurfaceControl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenEncoder implements Device.RotationListener {

    private static final int DEFAULT_FRAME_RATE = 60;
    private static final int DEFAULT_I_FRAME_INTERVAL = 10;
    private static final int REPEAT_FRAME_DELAY = 6;
    private static final int MICROSECONDS_IN_ONE_SECOND = 1_000_000;
    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private int bitRate;
    private int frameRate;
    private int iFrameInterval;

    public ScreenEncoder(int bitRate, int frameRate, int iFrameInterval) {
        this.bitRate = bitRate;
        this.frameRate = frameRate;
        this.iFrameInterval = iFrameInterval;
    }

    public ScreenEncoder(int bitRate) {
        this(bitRate, DEFAULT_FRAME_RATE, DEFAULT_I_FRAME_INTERVAL);
    }

    // 修改：支持传入编码器名称
    private static MediaCodec createCodec(String encoderName) throws IOException {
        if (encoderName != null && !encoderName.isEmpty() && !encoderName.equals("-")) {
            try {
                Ln.d("Creating encoder by name: " + encoderName);
                return MediaCodec.createByCodecName(encoderName);
            } catch (IOException | IllegalArgumentException e) {
                Ln.e("Failed to create encoder by name: " + encoderName, e);
            }
        }
        return MediaCodec.createEncoderByType("video/avc");
    }

    private static MediaFormat createFormat(int bitRate, int frameRate, int iFrameInterval) throws IOException {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, MICROSECONDS_IN_ONE_SECOND * REPEAT_FRAME_DELAY / frameRate);
        return format;
    }

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    private void startAudioCapture(OutputStream outputStream) {
        new Thread(() -> {
            AudioEncoder audioEncoder = new AudioEncoder(128000);
            try {
                audioEncoder.streamScreen(outputStream);
            } catch (Exception e) {
                Ln.e("audio capture Exception", e);
            }
        }).start();
    }

    public void streamScreen(Device device, OutputStream outputStream) throws IOException {
        int[] buf = new int[]{device.getScreenInfo().getDeviceSize().getWidth(), device.getScreenInfo().getDeviceSize().getHeight()};
        final byte[] array = new byte[buf.length * 4];
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        outputStream.write(array, 0, array.length);

        startAudioCapture(outputStream);

        MediaFormat format = createFormat(bitRate, frameRate, iFrameInterval);
        device.setRotationListener(this);
        boolean alive;
        int errorCount = 0;
        ScreenCapture capture = new ScreenCapture(device);
        try {
            do {
                // 关键修改：从 Device 获取 Options 中的 EncoderName
                MediaCodec codec = createCodec(device.getOptions().getEncoderName());
                Rect videoRect = device.getScreenInfo().getVideoSize().toRect();
                setSize(format, videoRect.width(), videoRect.height());
                configure(codec, format);
                Surface surface = null;

                try {
                    surface = codec.createInputSurface();
                    capture.start(surface);
                    codec.start();
                    alive = encode(codec, outputStream);
                    errorCount = 0;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Ln.e("Encoding error: " + e.getClass().getName(), e);
                    if (errorCount > 3) throw e;
                    errorCount++;
                    alive = true;
                } finally {
                    codec.stop();
                    codec.release();
                    if (surface != null) surface.release();
                }
            } while (alive);
        } finally {
            capture.release();
        }
    }

    @SuppressLint("NewApi")
    private boolean encode(MediaCodec codec, OutputStream outputStream) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange()) break;
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] b = new byte[outputBuffer.remaining()];
                        outputBuffer.get(b);

                        MediaPacket.Type type = MediaPacket.Type.VIDEO;
                        VideoPacket.Flag flag = VideoPacket.Flag.CONFIG;

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            flag = VideoPacket.Flag.END;
                        } else if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            flag = VideoPacket.Flag.KEY_FRAME;
                        } else if (bufferInfo.flags == 0) {
                            flag = VideoPacket.Flag.FRAME;
                        }
                        VideoPacket packet = new VideoPacket(type, flag, bufferInfo.presentationTimeUs, b);
                        outputStream.write(packet.toByteArray());
                    }
                }
            } finally {
                if (outputBufferId >= 0) codec.releaseOutputBuffer(outputBufferId, false);
            }
        }
        return !eof;
    }
}
