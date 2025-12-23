package org.client.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
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
import java.util.concurrent.atomic.AtomicReference;

public class Scrcpy extends Service {

    public static final String LOCAL_IP = "127.0.0.1";
    public static final int LOCAL_FORWART_PORT = 7008;

    public static final int DEFAULT_ADB_PORT = 5555;
    private static final String CHANNEL_ID = "ScrcpyServiceChannel";

    private String serverHost;
    private int serverPort = DEFAULT_ADB_PORT;
    private int screenWidth;
    private int screenHeight;

    private final Queue<byte[]> event = new LinkedList<byte[]>();
    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;
    
    // 缓存 SPS/PPS 参数，用于解码器重启
    private VideoPacket.StreamSettings cachedStreamSettings = null;

    private final IBinder mBinder = new MyServiceBinder();
    private boolean first_time = true;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    private boolean socket_status = false;

    // 使用 AtomicReference 线程安全地持有当前 Surface
    private final AtomicReference<Surface> currentSurfaceRef = new AtomicReference<>(null);
    // 标记是否需要重启解码器
    private final AtomicBoolean needDecoderRestart = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // 启动前台服务，极大降低被系统杀死的概率
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setContentTitle("Scrcpy Mobile")
                .setContentText("Service is running in background...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Scrcpy Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    // 外部调用此方法更新 Surface
    public void setNewSurface(Surface newSurface) {
        Surface oldSurface = currentSurfaceRef.get();
        if (oldSurface != newSurface) {
            currentSurfaceRef.set(newSurface);
            if (newSurface != null) {
                // 如果设置了新的有效 Surface，标记需要重启解码器
                needDecoderRestart.set(true);
            }
        }
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
        
        currentSurfaceRef.set(surface); // 初始化 Surface
        LetServceRunning.set(true);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                startConnection(serverHost, serverPort, delay);
            }
        });
        thread.start();
    }
    
    // 不再需要手动 pause/resume，由 setParms/setNewSurface 自动管理
    public void pause() { }
    public void resume() { }

    public void StopService() {
        LetServceRunning.set(false);
        // 清空回调防止崩溃
        serviceCallbacks = null;
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
        }
        stopForeground(true);
        stopSelf();
    }


    public boolean touchevent(MotionEvent touch_event, boolean landscape, int displayW, int displayH) {
        float remoteW;
        float remoteH;
        float realH;
        float realW;

        if (landscape) {  // 横屏的话，宽高相反
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
            case MotionEvent.ACTION_MOVE: // 所有手指移动
                for (int i = 0; i < touch_event.getPointerCount(); i++) {
                    int pointerId = touch_event.getPointerId(i);
                    int x = (int) touch_event.getX(i);
                    int y = (int) touch_event.getY(i);
                    sendTouchEvent(MotionEvent.ACTION_MOVE, touch_event.getButtonState(), 
                            (int) (x * realW / displayW), (int) (y * realH / displayH), pointerId);
                }
                break;

            case MotionEvent.ACTION_DOWN:          // 第一个手指按下
            case MotionEvent.ACTION_UP:            // 最后一个手指抬起
            case MotionEvent.ACTION_POINTER_DOWN:  // 其他手指按下
            case MotionEvent.ACTION_POINTER_UP:    // 其他手指抬起
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
                socket_status = false;
            }

        }

    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay) throws InterruptedException {
        byte[] packetSize = new byte[4];
        long lastVideoOffset = 0;
        long lastAudioOffset = 0;

        while (LetServceRunning.get()) {
            boolean waitEvent = true;
            try {
                // 1. 发送事件（即使在后台也允许发送，虽然可能没用，但要防止阻塞）
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

                // 2. 读取数据
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
                    byte[] packet = new byte[size];
                    dataInputStream.readFully(packet, 0, size);
                    
                    // --- 视频数据处理 ---
                    if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.VIDEO) {
                        VideoPacket videoPacket = VideoPacket.readHead(packet);
                        
                        // A. 收到配置帧：解析并缓存
                        if (videoPacket.flag == VideoPacket.Flag.CONFIG) {
                            int dataLength = packet.length - VideoPacket.getHeadLen();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(packet, VideoPacket.getHeadLen(), data, 0, dataLength);
                            
                            // 缓存参数，关键！
                            cachedStreamSettings = VideoPacket.getStreamSettings(data);
                            
                            // 如果当前有 Surface，则配置解码器
                            Surface s = currentSurfaceRef.get();
                            if (s != null && cachedStreamSettings != null) {
                                videoDecoder.configure(s, screenWidth, screenHeight, cachedStreamSettings.sps, cachedStreamSettings.pps);
                            }
                        } 
                        // B. 收到结束帧
                        else if (videoPacket.flag == VideoPacket.Flag.END) {
                            Log.e("Scrcpy", "END ... ");
                        } 
                        // C. 普通视频帧
                        else {
                            Surface s = currentSurfaceRef.get();
                            
                            // --- 核心：切回前台后的热重启逻辑 ---
                            // 如果标记需要重启解码器，且有 Surface 和缓存的参数
                            if (needDecoderRestart.getAndSet(false)) {
                                if (s != null && cachedStreamSettings != null) {
                                    Log.i("Scrcpy", "Restarting Decoder for new Surface");
                                    try {
                                        // 停止旧解码器，创建新解码器
                                        videoDecoder.stop();
                                        videoDecoder = new VideoDecoder();
                                        videoDecoder.start();
                                        // 使用缓存的 SPS/PPS 配置新解码器
                                        videoDecoder.configure(s, screenWidth, screenHeight, cachedStreamSettings.sps, cachedStreamSettings.pps);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            
                            // --- 数据空转逻辑 ---
                            // 如果 Surface 为空（在后台），我们已经读取了数据包（readFully），
                            // 直接不调用 decodeSample 即可实现丢弃，保持 socket 流同步。
                            if (s != null) {
                                if (lastVideoOffset == 0) {
                                    lastVideoOffset = System.currentTimeMillis() - (videoPacket.presentationTimeStamp / 1000);
                                }
                                if (videoPacket.flag == VideoPacket.Flag.KEY_FRAME) {
                                    videoDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - VideoPacket.getHeadLen(),
                                            0, videoPacket.flag.getFlag());
                                } else {
                                    if (System.currentTimeMillis() - (lastVideoOffset + (videoPacket.presentationTimeStamp / 1000)) < delay) {
                                        videoDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - VideoPacket.getHeadLen(),
                                                0, videoPacket.flag.getFlag());
                                    }
                                }
                            }
                        }
                        first_time = false;
                    } 
                    // --- 音频数据处理 ---
                    else if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.AUDIO) {
                        AudioPacket audioPacket = AudioPacket.readHead(packet);
                        if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                            int dataLength = packet.length - AudioPacket.getHeadLen();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(packet, AudioPacket.getHeadLen(), data, 0, dataLength);
                            audioDecoder.configure(data);
                        } else if (audioPacket.flag == AudioPacket.Flag.END) {
                            Log.e("Scrcpy", "Audio END ... ");
                        } else {
                            if (lastAudioOffset == 0) {
                                lastAudioOffset = System.currentTimeMillis() - (audioPacket.presentationTimeStamp / 1000);
                            }
                            if (System.currentTimeMillis() - (lastAudioOffset + (audioPacket.presentationTimeStamp / 1000)) < delay) {
                                audioDecoder.decodeSample(packet, VideoPacket.getHeadLen(), packet.length - AudioPacket.getHeadLen(),
                                        0, audioPacket.flag.getFlag());
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
