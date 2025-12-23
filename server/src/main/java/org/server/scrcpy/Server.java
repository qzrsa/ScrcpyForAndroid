package org.server.scrcpy;

import org.server.scrcpy.util.Workarounds;
import java.io.IOException;

public final class Server {

    private static String ip = null;

    private Server() {}

    private static void scrcpy(Options options) throws IOException {
        Workarounds.apply();
        final Device device = new Device(options);
        try (DroidConnection connection = DroidConnection.open(ip)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options.getBitRate());
            startEventController(device, connection);
            try {
                screenEncoder.streamScreen(device, connection.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
                Ln.d("Screen streaming stopped");
            }
        }
    }

    private static void startEventController(final Device device, final DroidConnection connection) {
        new Thread(() -> {
            try {
                new EventController(device, connection).control();
            } catch (IOException e) {
                Ln.d("Event controller stopped");
            }
        }).start();
    }

    private static Options createOptions(String... args) {
        Options options = new Options();
        if (args.length < 1) return options;
        ip = String.valueOf(args[0]);

        if (args.length < 2) return options;
        int maxSize = Integer.parseInt(args[1]) & ~7;
        options.setMaxSize(maxSize);

        if (args.length < 3) return options;
        int bitRate = Integer.parseInt(args[2]);
        options.setBitRate(bitRate);

        if (args.length < 4) return options;
        boolean tunnelForward = Boolean.parseBoolean(args[3]);
        options.setTunnelForward(tunnelForward);

        // 新增：读取第 5 个参数 (编码器名称)
        if (args.length >= 5) {
            String encoderName = args[4];
            if (encoderName != null && !encoderName.equals("-")) {
                options.setEncoderName(encoderName);
            }
        }
        return options;
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Ln.e("Exception on thread " + t, e));
        try {
            // 删除旧的 jar，确保干净启动 (虽然这个是在 Server 内部运行，但也起个保险作用)
            Process cmd = Runtime.getRuntime().exec("rm /data/local/tmp/scrcpy-server.jar");
            cmd.waitFor();
        } catch (Exception e1) {
            e1.printStackTrace();
        }
        Options options = createOptions(args);
        scrcpy(options);
    }
}
