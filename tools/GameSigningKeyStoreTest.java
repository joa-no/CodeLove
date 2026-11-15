package org.love2d.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/** No Android SDK or network: test key generation, protected backup and restore. */
public final class GameSigningKeyStoreTest {
    private GameSigningKeyStoreTest() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void ensureRejected(Throwing action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Operation should have been rejected");
        } catch (GeneralSecurityException | java.io.IOException expected) {
            // Expected for malformed input, overwrite attempts or bad password.
        }
    }

    interface Throwing {
        void run() throws Exception;
    }

    private static void test() throws Exception {
        File directory = Files.createTempDirectory("codelove-game-signing-").toFile();
        char[] password = "Sample-Backup-Password-2026".toCharArray();
        char[] wrong = "The-Wrong-Password-2026".toCharArray();
        try {
            File original = new File(directory, "first-game.p12");
            File restored = new File(directory, "restored-game.p12");
            File independent = new File(directory, "another-game.p12");

            ensureRejected(() -> GameSigningKeyStore.create(original, "short".toCharArray()));
            check(!original.exists(), "Short-password failure created a key file");
            GameSigningKeyStore.Identity first = GameSigningKeyStore.create(original, password);
            check(first.sha256.length() == 64, "Certificate fingerprint is missing");
            check("game-key".equals(first.alias), "Unexpected signing key alias");
            byte[] originalBytes = Files.readAllBytes(original.toPath());
            check(originalBytes.length > 1000, "Encrypted PKCS12 backup is too short");

            GameSigningKeyStore.Identity again =
                    GameSigningKeyStore.inspect(original, password);
            check(first.sha256.equals(again.sha256), "Reading changed game identity");
            ensureRejected(() -> GameSigningKeyStore.inspect(original, wrong));
            ensureRejected(() -> GameSigningKeyStore.create(original, password));
            check(Arrays.equals(originalBytes, Files.readAllBytes(original.toPath())),
                  "An overwrite attempt damaged the existing game key");

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            GameSigningKeyStore.Identity backedUp =
                    GameSigningKeyStore.backup(original, password, buffer);
            check(backedUp.sha256.equals(first.sha256),
                  "Backup changed the certificate identity");
            check(Arrays.equals(originalBytes, buffer.toByteArray()),
                  "Backup did not copy the original PKCS12 bytes");

            ensureRejected(() -> GameSigningKeyStore.restore(
                    new ByteArrayInputStream(buffer.toByteArray()), restored, wrong));
            check(!restored.exists(), "Wrong password created a signing identity");

            GameSigningKeyStore.Identity recovered = GameSigningKeyStore.restore(
                    new ByteArrayInputStream(buffer.toByteArray()), restored, password);
            check(recovered.sha256.equals(first.sha256),
                  "Restoring backup changed signing identity");
            ensureRejected(() -> GameSigningKeyStore.restore(
                    new ByteArrayInputStream(buffer.toByteArray()), restored, password));
            check(Arrays.equals(originalBytes, Files.readAllBytes(restored.toPath())),
                  "Overwrite attempt changed restored backup");

            // Generated PKCS12 must be compatible with the exact provider-
            // neutral KeyStore API that OfflineApkSigner currently uses.
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(restored)) {
                store.load(in, password);
            }
            Key key = store.getKey("game-key", password);
            check(key instanceof PrivateKey, "Recovered private key not usable");
            X509Certificate cert = (X509Certificate) store.getCertificate("game-key");
            byte[] challenge = "CodeLove test signing identity".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign((PrivateKey) key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(cert.getPublicKey());
            verifier.update(challenge);
            check(verifier.verify(signature), "Recovered key cannot verify a signature");

            GameSigningKeyStore.Identity other =
                    GameSigningKeyStore.create(independent, password);
            check(!first.sha256.equals(other.sha256),
                  "Different games must have independent signing certificates");

            byte[] fakeBackup = new byte[]{1, 2, 3, 4, 5, 6};
            File rejectedFile = new File(directory, "rejected.p12");
            ensureRejected(() -> GameSigningKeyStore.restore(
                    new ByteArrayInputStream(fakeBackup), rejectedFile, password));
            check(!rejectedFile.exists(), "Invalid backup was published");

            System.out.println("GameSigningKeyStore tests passed: generate, backup, restore, identity");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(wrong, '\0');
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        test();
    }
}
