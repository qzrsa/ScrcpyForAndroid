package org.server.scrcpy;

public class Options {
    private int maxSize;
    private int bitRate;
    private boolean tunnelForward;
    private String encoderName; // 新增：编码器名称

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public void setTunnelForward(boolean tunnelForward) {
        this.tunnelForward = tunnelForward;
    }

    // 新增 Getter
    public String getEncoderName() {
        return encoderName;
    }

    // 新增 Setter
    public void setEncoderName(String encoderName) {
        this.encoderName = encoderName;
    }
}
