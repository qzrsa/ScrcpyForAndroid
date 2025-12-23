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
    // 新增：保存 Options 对象
    private final Options options;

    public Device(Options options) {
        this.options = options;
        screenInfo = computeScreenInfo(options.getMaxSize());
        registerRotationWatcher(new IRotationWatcher.Stub() {
            @Override
            public void onRotationChanged(int rotation) throws RemoteException {
                synchronized (Device.this) {
                    screenInfo = screenInfo.withRotation(rotation);
                    if (rotationListener != null) {
                        rotationListener.onRotationChanged(rotation);
                    }
                }
            }
        });
    }

    // 新增 Getter
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
        int w = deviceSize.getWidth() & ~7;
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
        @SuppressWarnings("checkstyle:HiddenField")
        ScreenInfo screenInfo = getScreenInfo();
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
