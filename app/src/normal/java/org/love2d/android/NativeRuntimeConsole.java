package org.love2d.android;

import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Process-local, bounded stdout/stderr capture for the standalone runtime. */
final class NativeRuntimeConsole implements AutoCloseable {
    private static final int LIMIT = 32768;
    private final StringBuilder tail = new StringBuilder();
    private FileDescriptor savedOut, savedErr, readEnd, writeEnd;
    private Thread reader;

    NativeRuntimeConsole() throws Exception {
        try {
            savedOut = Os.dup(FileDescriptor.out);
            savedErr = Os.dup(FileDescriptor.err);
            FileDescriptor[] pipe = Os.pipe();
            readEnd = pipe[0];
            writeEnd = pipe[1];
            reader = new Thread(this::readLoop, "CodeLove-native-console");
            reader.setDaemon(true);
            reader.start();
            Os.dup2(writeEnd, 1);
            Os.dup2(writeEnd, 2);
            closeFd(writeEnd);
            writeEnd = null;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void readLoop() {
        try (InputStreamReader input = new InputStreamReader(
                new FileInputStream(readEnd), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                synchronized (tail) {
                    tail.append(buffer, 0, count);
                    if (tail.length() > LIMIT) tail.delete(0, tail.length() - LIMIT);
                }
            }
        } catch (Exception ignored) {
            // Do not write to stderr from the capture thread.
        }
    }

    String snapshot() {
        synchronized (tail) { return tail.toString(); }
    }

    private static void closeFd(FileDescriptor fd) {
        if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
    }

    @Override public void close() {
        if (savedOut != null) try { Os.dup2(savedOut, 1); } catch (Exception ignored) {}
        if (savedErr != null) try { Os.dup2(savedErr, 2); } catch (Exception ignored) {}
        closeFd(savedOut);
        closeFd(savedErr);
        closeFd(writeEnd);
        if (reader == null) closeFd(readEnd);
        savedOut = savedErr = writeEnd = null;
    }
}
