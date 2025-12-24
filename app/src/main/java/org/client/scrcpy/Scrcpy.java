package org.client.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.client.scrcpy.decoder.AudioDecoder;
import org.client.scrcpy.decoder.VideoDecoder;
import org.client.scrcpy.model.AudioPacket;
import org.client.scrcpy.model.ByteUtils;
import org.client.scrcpy.model.MediaPacket;
import org.client.scrcpy.model.VideoPacket;
import org.client.scrcpy.utils.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Scrcpy extends Service {

    public static final String LOCAL_IP = "127.0.0.1";
    public static final int LOCAL_FORWART_PORT = 7008;

    public static final int DEFAULT_ADB_PORT = 5555;
    private String serverHost;
    private int serverPort = DEFAULT_ADB_PORT;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;

    private final Queue<byte[]> event = new LinkedList<byte[]>();
    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final IBinder mBinder = new MyServiceBinder();
    private boolean first_time = true;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    private boolean socket_status = false;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public void setParms(Surface NewSurface, int NewWidth, int NewHeight) {
        this.screenWidth = NewWidth;
        this.screenHeight = NewHeight;
        this.surface = NewSurface;

        videoDecoder.start();
        audioDecoder.start();

        updateAvailable.set(true);
    }

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int delay) {
        this.videoDecoder = new VideoDecoder();
        videoDecoder.start();

        this.audioDecoder = new AudioDecoder();
        audioDecoder.start();

        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        this.serverHost = serverInfo[0];
        this.serverPort = Integer.parseInt(serverInfo[1]);

        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.surface = surface;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                startConnection(serverHost, serverPort, delay);
            }
        });
        thread.start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        if (audioDecoder != null) {
            audioDecoder.start();
        }
        updateAvailable.set(true);
    }

    public void StopService() {
        LetServceRunning.set(false);
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
        }
        stopSelf();
    }


    public boolean touchevent(MotionEvent touch_event, boolean landscape, int displayW, int displayH) {
        float remoteW;
        float remoteH;
        float realH;
        float realW;

        if (landscape) {
            remoteW = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);

            realW = Math.min(remoteW, screenWidth);
            realH = realW * remoteH / remoteW;
        } else {
            remoteW = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            realH = Math.min(remoteH, screenHeight);
            realW = realH * remoteW / remoteH;
        }

        int actionMasked = touch_event.getActionMasked();
        int actionIndex = touch_event.getActionIndex();

        switch (actionMasked) {
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < touch_event.getPointerCount(); i++) {
                    int pointerId = touch_event.getPointerId(i);
                    int x = (int) touch_event.getX(i);
                    int y = (int) touch_event.getY(i);
                    sendTouchEvent(MotionEvent.ACTION_MOVE, touch_event.getButtonState(),
                            (int) (x * realW / displayW), (int) (y * realH / displayH), pointerId);
                }
                break;

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                int pointerId = touch_event.getPointerId(actionIndex);
                int x = (int) touch_event.getX(actionIndex);
                int y = (int) touch_event.getY(actionIndex);

                sendTouchEvent(actionMasked, touch_event.getButtonState(),
                        (int) (x * realW / displayW), (int) (y * realH / displayH), pointerId);
                break;

            default:
                int defaultId = touch_event.getPointerId(actionIndex);
                sendTouchEvent(actionMasked, touch_event.getButtonState(),
                        (int) (touch_event.getX(actionIndex) * realW / displayW),
                        (int) (touch_event.getY(actionIndex) * realH / displayH), defaultId);
                break;
        }

        return true;
    }

    private void sendTouchEvent(int action, int buttonState, int x, int y, int pointerId){
        int[] buf = new int[]{action, buttonState, x, y, pointerId};
        final byte[] array = new byte[buf.length * 4];
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
        }
    }

    public int[] get_remote_device_resolution() {
        return remote_dev_resolution;
    }

    public boolean check_socket_connection() {
        return socket_status;
    }

    public void sendKeyevent(int keycode) {
        int[] buf = new int[]{keycode};

        final byte[] array = new byte[buf.length * 4];
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
        }
    }

    private void startConnection(String ip, int port, int delay) {

        videoDecoder = new VideoDecoder();
        videoDecoder.start();
        audioDecoder = new AudioDecoder();
        audioDecoder.start();

        DataInputStream dataInputStream = null;
        DataOutputStream dataOutputStream = null;
        Socket socket = null;
        boolean firstConnect = true;
        int attempts = 50;
        while (attempts > 0) {
            try {
                Log.e("Scrcpy", "Connecting to " + LOCAL_IP);
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000);
                if (!LetServceRunning.get()) {
                    return;
                }

                Log.e("Scrcpy", "Connecting to " + LOCAL_IP + " success");

                if (firstConnect) {
                    firstConnect = false;
                    attempts = 5;
                }
                dataInputStream = new DataInputStream(socket.getInputStream());
                int waitResolutionCount = 10;
                while (dataInputStream.available() <= 0 && waitResolutionCount > 0) {
                    waitResolutionCount--;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                if (dataInputStream.available() <= 0) {
                    throw new IOException("can't read socket Resolution : " + attempts);
                }

                dataOutputStream = new DataOutputStream(socket.getOutputStream());
                attempts = 0;
                byte[] buf = new byte[16];
                dataInputStream.read(buf, 0, 16);
                for (int i = 0; i < remote_dev_resolution.length; i++) {
                    remote_dev_resolution[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                            (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                            (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                            ((int) (buf[i * 4 + 3]) & 0xFF);
                }
                if (remote_dev_resolution[0] > remote_dev_resolution[1]) {
                    first_time = false;
                    int i = remote_dev_resolution[0];
                    remote_dev_resolution[0] = remote_dev_resolution[1];
                    remote_dev_resolution[1] = i;
                }
                socket_status = true;

                loop(dataInputStream, dataOutputStream, delay);

            } catch (Exception e) {
                e.printStackTrace();
                if (LetServceRunning.get()) {
                    attempts--;
                    if (attempts < 0) {
                        socket_status = false;

                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                Log.e("Scrcpy", e.getMessage());
                Log.e("Scrcpy", "attempts--");
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataInputStream != null) {
                    try {
                        dataInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                event.clear();
            }
        }
    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay) throws InterruptedException {
        VideoPacket.StreamSettings streamSettings = null;
        byte[] packetSize = new byte[4];

        byte[] buffer = new byte[2 * 1024 * 1024];

        long lastVideoOffset = 0;
        long lastAudioOffset = 0;
        int videoPassCount = 0;
        int audioPassCount = 0;

        while (LetServceRunning.get()) {
            boolean waitEvent = true;
            try {
                byte[] sendevent = event.poll();
                if (sendevent != null) {
                    waitEvent = false;
                    try {
                        dataOutputStream.write(sendevent, 0, sendevent.length);
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        LetServceRunning.set(false);
                    }
                }

                if (dataInputStream.available() > 0) {
                    waitEvent = false;
                    dataInputStream.readFully(packetSize, 0, 4);
                    int size = ByteUtils.bytesToInt(packetSize);
                    if (size > 4 * 1024 * 1024) {
                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        LetServceRunning.set(false);
                        return;
                    }

                    if (size > buffer.length) {
                        buffer = new byte[size];
                    }
                    dataInputStream.readFully(buffer, 0, size);

                    if (MediaPacket.Type.getType(buffer[0]) == MediaPacket.Type.VIDEO) {
                        VideoPacket videoPacket = VideoPacket.readHead(buffer);

                        if (videoPacket.flag == VideoPacket.Flag.CONFIG || updateAvailable.get()) {
                            if (!updateAvailable.get()) {
                                int dataLength = size - VideoPacket.getHeadLen();
                                byte[] data = new byte[dataLength];
                                System.arraycopy(buffer, VideoPacket.getHeadLen(), data, 0, dataLength);
                                streamSettings = VideoPacket.getStreamSettings(data);
                                if (!first_time) {
                                    if (serviceCallbacks != null) {
                                        serviceCallbacks.loadNewRotation();
                                    }
                                    while (!updateAvailable.get()) {
                                        try {
                                            Thread.sleep(100);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                            updateAvailable.set(false);
                            if (streamSettings != null) {
                                videoDecoder.configure(surface, screenWidth, screenHeight, streamSettings.sps, streamSettings.pps);
                            }
                        } else if (videoPacket.flag == VideoPacket.Flag.END) {
                            Log.e("Scrcpy", "END ... ");
                        } else {
                            if (lastVideoOffset == 0) {
                                lastVideoOffset = System.currentTimeMillis() - (videoPacket.presentationTimeStamp / 1000);
                            }
                            if (videoPacket.flag == VideoPacket.Flag.KEY_FRAME) {
                                videoDecoder.decodeSample(buffer, VideoPacket.getHeadLen(), size - VideoPacket.getHeadLen(),
                                        0, videoPacket.flag.getFlag());
                            } else {
                                if (System.currentTimeMillis() - (lastVideoOffset + (videoPacket.presentationTimeStamp / 1000)) < delay) {
                                    videoPassCount = 0;
                                    videoDecoder.decodeSample(buffer, VideoPacket.getHeadLen(), size - VideoPacket.getHeadLen(),
                                            0, videoPacket.flag.getFlag());
                                } else {
                                    videoPassCount++;
                                }
                            }
                        }
                        first_time = false;
                    } else if (MediaPacket.Type.getType(buffer[0]) == MediaPacket.Type.AUDIO) {
                        AudioPacket audioPacket = AudioPacket.readHead(buffer);
                        if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                            int dataLength = size - AudioPacket.getHeadLen();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(buffer, AudioPacket.getHeadLen(), data, 0, dataLength);
                            audioDecoder.configure(data);
                        } else if (audioPacket.flag == AudioPacket.Flag.END) {
                            Log.e("Scrcpy", "Audio END ... ");
                        } else {
                            if (lastAudioOffset == 0) {
                                lastAudioOffset = System.currentTimeMillis() - (audioPacket.presentationTimeStamp / 1000);
                            }
                            if (System.currentTimeMillis() - (lastAudioOffset + (audioPacket.presentationTimeStamp / 1000)) < delay) {
                                audioPassCount = 0;
                                audioDecoder.decodeSample(buffer, VideoPacket.getHeadLen(), size - AudioPacket.getHeadLen(),
                                        0, audioPacket.flag.getFlag());
                            } else {
                                audioPassCount++;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.e("Scrcpy", "IOException: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (waitEvent) {
                    Thread.sleep(5);
                }
            }
        }
    }

    public interface ServiceCallbacks {
        void loadNewRotation();

        void errorDisconnect();
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }
}
