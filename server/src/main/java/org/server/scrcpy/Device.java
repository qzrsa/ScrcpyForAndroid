package org.server.scrcpy;

import org.server.scrcpy.device.Point;
import android.os.Build;
import android.os.RemoteException;
import android.view.IRotationWatcher;
import android.view.InputEvent;

import org.server.scrcpy.wrappers.ServiceManager;

public final class Device {

    private ScreenInfo screenInfo;
    private RotationListener rotationListener;
    // 1. 新增：保存 Options 对象 (用于编码器选择)
    private final Options options;

    public Device(Options options) {
        this.options = options;
        screenInfo = computeScreenInfo(options.getMaxSize());
        registerRotationWatcher(new IRotationWatcher.Stub() {
            @Override
            public void onRotationChanged(int rotation) throws RemoteException {
                synchronized (Device.this) {
                    screenInfo = screenInfo.withRotation(rotation);
                    // notify
                    if (rotationListener != null) {
                        rotationListener.onRotationChanged(rotation);
                    }
                }
            }
        });
    }

    // 2. 新增：Getter 方法
    public Options getOptions() {
        return options;
    }

    public static String getDeviceName() {
        return Build.MODEL;
    }

    public synchronized ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private ScreenInfo computeScreenInfo(int maxSize) {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo();
        boolean rotated = (displayInfo.getRotation() & 1) != 0;
        Size deviceSize = displayInfo.getSize();
        int w = deviceSize.getWidth() & ~7; // in case it's not a multiple of 8
        int h = deviceSize.getHeight() & ~7;
        if (maxSize > 0) {
            if (BuildConfig.DEBUG && maxSize % 8 != 0) {
                throw new AssertionError("Max size must be a multiple of 8");
            }
            boolean portrait = h > w;
            int major = portrait ? h : w;
            int minor = portrait ? w : h;
            if (major > maxSize) {
                int minorExact = minor * maxSize / major;
                // +4 to round the value to the nearest multiple of 8
                minor = (minorExact + 4) & ~7;
                major = maxSize;
            }
            w = portrait ? minor : major;
            h = portrait ? major : minor;
        }
        Size videoSize = new Size(w, h);
        return new ScreenInfo(deviceSize, videoSize, rotated);
    }

    public Point getPhysicalPoint(Position position) {
        @SuppressWarnings("checkstyle:HiddenField") // it hides the field on purpose, to read it with a lock
        ScreenInfo screenInfo = getScreenInfo(); // read with synchronization
        Size videoSize = screenInfo.getVideoSize();
        Size clientVideoSize = position.getScreenSize();
        if (!videoSize.equals(clientVideoSize)) {
            return null;
        }
        Size deviceSize = screenInfo.getDeviceSize();
        Point point = position.getPoint();
        int scaledX = point.getX() * deviceSize.getWidth() / videoSize.getWidth();
        int scaledY = point.getY() * deviceSize.getHeight() / videoSize.getHeight();
        return new Point(scaledX, scaledY);
    }

    // 3. 补回：缺失的自定义方法，解决编译报错
    public Point NewgetPhysicalPoint(Point point) {
        @SuppressWarnings("checkstyle:HiddenField")
        ScreenInfo screenInfo = getScreenInfo();
        Size videoSize = screenInfo.getVideoSize();
        Size deviceSize = screenInfo.getDeviceSize();
        int scaledX = point.getX() * deviceSize.getWidth() / videoSize.getWidth();
        int scaledY = point.getY() * deviceSize.getHeight() / videoSize.getHeight();
        return new Point(scaledX, scaledY);
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        return ServiceManager.getInputManager().injectInputEvent(inputEvent, mode);
    }

    public boolean isScreenOn() {
        return ServiceManager.getPowerManager().isScreenOn();
    }

    public void registerRotationWatcher(IRotationWatcher rotationWatcher) {
        ServiceManager.getWindowManager().registerRotationWatcher(rotationWatcher);
    }

    public synchronized void setRotationListener(RotationListener rotationListener) {
        this.rotationListener = rotationListener;
    }

    public interface RotationListener {
        void onRotationChanged(int rotation);
    }
}
