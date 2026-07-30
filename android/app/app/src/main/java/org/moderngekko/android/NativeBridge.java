package org.moderngekko.android;

public final class NativeBridge {
    static {
        System.loadLibrary("moderngekko_android");
    }

    private NativeBridge() {
    }

    public static native String nativeVersion();

    public static native String inspectExtractedGame(String gameRoot);
}
