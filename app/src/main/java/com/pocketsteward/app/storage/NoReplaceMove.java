package com.pocketsteward.app.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Kernel-enforced no-replace rename. Never fall back to an overwriting rename. */
public final class NoReplaceMove {
    public static final int CROSS_DEVICE = 18;
    private static final Throwable LOAD_FAILURE;
    static {
        Throwable failure = null;
        try { System.loadLibrary("pocketsteward_fs"); }
        catch (LinkageError | SecurityException e) { failure = e; }
        LOAD_FAILURE = failure;
    }
    private NoReplaceMove() {}

    /** A regular file may use the verified exclusive-copy path when no-replace rename is unsupported. */
    public static boolean permitsCopyFallback(int error) {
        return error == CROSS_DEVICE || error == 22 || error == 38 || error == 95;
    }

    /** Zero means success; otherwise the native errno describes the refusal. */
    public static int move(File source, File destination) throws IOException {
        if (LOAD_FAILURE != null) throw new IOException("Safe filesystem move is unavailable on this device.", LOAD_FAILURE);
        try {
            return renamePaths(source.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                    destination.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        } catch (LinkageError failure) {
            throw new IOException("Safe filesystem move is unavailable on this device.", failure);
        }
    }
    private static native int renamePaths(byte[] source, byte[] destination);
}
