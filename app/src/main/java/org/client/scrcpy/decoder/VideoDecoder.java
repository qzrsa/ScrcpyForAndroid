package org.client.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private MediaCodec mCodec;
    private Worker mWorker;
    private final AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    // 控制是否渲染到屏幕
    private final AtomicBoolean mShouldRender = new AtomicBoolean(true);

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, csd0, csd1);
        }
    }

    // 新增：热切换 Surface
    public void updateSurface(Surface newSurface) {
        if (mWorker != null) {
            mWorker.setSurface(newSurface);
        }
    }

    // 新增：控制渲染状态
    public void setRender(boolean render) {
        mShouldRender.set(render);
    }

    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
        // 默认开启渲染
        mShouldRender.set(true);
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            mIsConfigured.set(false);
            if (mCodec != null) {
                try {
                    mCodec.stop();
                    mCodec.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                mCodec = null;
            }
        }
    }

    private class Worker extends Thread {

        private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
        // 保存当前的配置参数，用于重建
        private Surface mCurrentSurface;
        private int mWidth, mHeight;
        private ByteBuffer mCsd0, mCsd1;

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        // 动态切换 Surface
        private void setSurface(Surface surface) {
            mCurrentSurface = surface;
            if (mCodec != null && mIsConfigured.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        // API 23+ 支持不重启解码器切换 Surface
                        Log.i("Scrcpy", "Hot-swapping output surface...");
                        mCodec.setOutputSurface(surface);
                    } catch (Exception e) {
                        Log.e("Scrcpy", "Failed to setOutputSurface", e);
                        // 如果失败，强制重启
                        configure(surface, mWidth, mHeight, mCsd0, mCsd1);
                    }
                } else {
                    // 低版本不支持热切换，必须重启（会黑屏一会）
                    configure(surface, mWidth, mHeight, mCsd0, mCsd1);
                }
            }
        }

        private void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
            mCurrentSurface = surface;
            mWidth = width;
            mHeight = height;
            mCsd0 = csd0;
            mCsd1 = csd1;

            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                if (mCodec != null) {
                    try {
                        mCodec.stop();
                        mCodec.release();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            try {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setByteBuffer("csd-0", csd0);
                format.setByteBuffer("csd-1", csd1);
                mCodec = MediaCodec.createDecoderByType("video/avc");
                mCodec.configure(format, surface, null, 0);
                mCodec.start();
                mIsConfigured.set(true);
                Log.i("Scrcpy", "Decoder configured");
            } catch (Exception e) {
                Log.e("Scrcpy", "Failed to create codec", e);
                throw new RuntimeException("Failed to create codec", e);
            }
        }


        @SuppressWarnings("deprecation")
        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (mIsConfigured.get() && mIsRunning.get() && mCodec != null) {
                try {
                    int index = mCodec.dequeueInputBuffer(-1);
                    if (index >= 0) {
                        ByteBuffer buffer;
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                            buffer = mCodec.getInputBuffers()[index];
                            buffer.clear();
                        } else {
                            buffer = mCodec.getInputBuffer(index);
                        }
                        if (buffer != null) {
                            buffer.put(data, offset, size);
                            mCodec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                        }
                    }
                } catch (Exception e) {
                    // 解码错误通常忽略，等待关键帧恢复
                    Log.e("Scrcpy", "Decode error: " + e.getMessage());
                }
            }
        }

        @Override
        public void run() {
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (mIsRunning.get()) {
                    if (mIsConfigured.get() && mCodec != null) {
                        int index = -1;
                        try {
                            index = mCodec.dequeueOutputBuffer(info, 0);
                        } catch (Exception e) {
                            Log.e("Scrcpy", "dequeueOutputBuffer error", e);
                            break;
                        }

                        if (index >= 0) {
                            // 关键逻辑：
                            // 如果 mShouldRender 为 true，则渲染到 Surface (releaseOutputBuffer true)
                            // 如果 mShouldRender 为 false（后台），则仅释放缓冲区但不渲染 (releaseOutputBuffer false)
                            // 这样解码器状态一直保持，切回来时有参考帧，不会黑屏！
                            boolean doRender = mShouldRender.get();
                            mCodec.releaseOutputBuffer(index, doRender);
                            
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 格式变化，通常不需要处理
                        }
                    } else {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("Scrcpy", "Decoder worker error", e);
            }
        }
    }
}
