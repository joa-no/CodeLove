package org.love2d.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * JVM-only test/driver. Production keys and passwords must never be passed
 * as command-line arguments or recorded in workflow logs.
 */
public final class OfflineApkSignerTest {
    private OfflineApkSignerTest() {}

    private static void expectRejection(File apk, File out, File keyFile)
            throws Exception {
        try {
            OfflineApkSigner.sign(apk, out, keyFile, new char[]{'t'}, null);
            throw new AssertionError("Expected signing to reject invalid files");
        } catch (IOException expected) {
            // Missing or invalid input was rejected before touching output.
        }
    }

    private static void unit() throws Exception {
        File directory = Files.createTempDirectory("codelove-signer-test").toFile();
        try {
            File unsigned = new File(directory, "unsigned.apk");
            File key = new File(directory, "key.p12");
            File signed = new File(directory, "signed.apk");
            expectRejection(unsigned, signed, key);
            Files.write(unsigned.toPath(), new byte[]{1});
            expectRejection(unsigned, signed, key);
            Files.write(key.toPath(), new byte[]{1});
            expectRejection(unsigned, unsigned, key);
            System.out.println("OfflineApkSigner input checks passed");
        } finally {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
            directory.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            unit();
            return;
        }
        if (args.length == 5 && "--sign".equals(args[0])) {
            String fromEnvironment = System.getenv("GAME_TEST_KEY_PASSWORD");
            if (fromEnvironment == null || fromEnvironment.isEmpty()) {
                throw new IllegalStateException("Test signing password not provided");
            }
            char[] password = fromEnvironment.toCharArray();
            try {
                OfflineApkSigner.Result result = OfflineApkSigner.sign(
                    new File(args[1]), new File(args[2]), new File(args[3]),
                    password, args[4]);
                System.out.println("Signer certificate SHA256: "
                    + result.certificateSha256);
                System.out.println("Verified v1=" + result.v1 + " v2=" + result.v2
                    + " v3=" + result.v3);
            } finally {
                Arrays.fill(password, '\0');
            }
            return;
        }
        throw new IllegalArgumentException(
            "Usage: OfflineApkSignerTest [--sign unsigned.apk signed.apk key.p12 alias]");
    }
}
