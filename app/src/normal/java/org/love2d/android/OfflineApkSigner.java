package org.love2d.android;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Isolated offline APK signing experiment for CodeLove's normal flavor.
 *
 * Loads a *user-owned* PKCS#12 keystore; never uses the CodeLove app signing
 * key, and never creates or exports private keys. No network or UI interaction.
 *
 * APK assembly and native-library alignment must be completed BEFORE signing.
 * Run off the main thread and keep the input and output in app-private storage.
 *
 * This class relies on apksig, which was designed for host-side Java. JVM
 * tests do not prove compatibility on an Android device.
 */
final class OfflineApkSigner {
    static final class Result {
        final String certificateSha256;
        final boolean v1;
        final boolean v2;
        final boolean v3;

        Result(String certificateSha256, boolean v1, boolean v2, boolean v3) {
            this.certificateSha256 = certificateSha256;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
        }
    }

    private OfflineApkSigner() {}

    /**
     * @param alias specified key alias, or null to require precisely one key
     * @param password caller-owned password; this method does not persist it
     */
    static Result sign(File unsignedApk, File destination, File keyStoreFile,
                       char[] password, String alias)
            throws IOException, GeneralSecurityException {
        if (unsignedApk == null || destination == null || keyStoreFile == null
                || password == null || !unsignedApk.isFile()
                || !keyStoreFile.isFile() || unsignedApk.length() == 0
                || keyStoreFile.length() == 0) {
            throw new IOException("Missing APK or PKCS#12 signing key");
        }
        File canonicalInput = unsignedApk.getCanonicalFile();
        File canonicalOutput = destination.getCanonicalFile();
        if (canonicalInput.equals(canonicalOutput)
                || canonicalOutput.equals(keyStoreFile.getCanonicalFile())
                || canonicalInput.equals(keyStoreFile.getCanonicalFile())
                || destination.exists()) {
            throw new IOException("Signing output must be a new, separate file");
        }
        File directory = destination.getAbsoluteFile().getParentFile();
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("Signing output directory does not exist");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(keyStoreFile)) {
            store.load(input, password);
        }
        String actualAlias = alias;
        if (actualAlias == null || actualAlias.trim().isEmpty()) {
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String candidate = aliases.nextElement();
                if (store.isKeyEntry(candidate)) {
                    if (actualAlias != null) {
                        throw new GeneralSecurityException(
                            "Multiple signing keys; select an alias");
                    }
                    actualAlias = candidate;
                }
            }
        }
        if (actualAlias == null || !store.isKeyEntry(actualAlias)) {
            throw new GeneralSecurityException("Signing key alias not found");
        }
        Key key = store.getKey(actualAlias, password);
        if (!(key instanceof PrivateKey)) {
            throw new GeneralSecurityException("Alias does not contain a private key");
        }
        Certificate[] chain = store.getCertificateChain(actualAlias);
        if (chain == null || chain.length == 0) {
            throw new GeneralSecurityException("Signing certificate is missing");
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (!(certificate instanceof X509Certificate)) {
                throw new GeneralSecurityException("Invalid signing certificate chain");
            }
            certificates.add((X509Certificate) certificate);
        }

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "GAME", (PrivateKey) key, certificates).build();
        File temporary = File.createTempFile(
                ".codelove-sign-", ".apk", directory);
        try {
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(canonicalInput)
                    .setOutputApk(temporary)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setV4SigningEnabled(false)
                    .setAlignmentPreserved(true)
                    .build().sign();

            ApkVerifier.Result verification =
                    new ApkVerifier.Builder(temporary).build().verify();
            if (!verification.isVerified()
                    || !verification.isVerifiedUsingV2Scheme()
                    || verification.getSignerCertificates().size() != 1
                    || !Arrays.equals(
                        verification.getSignerCertificates().get(0).getEncoded(),
                        certificates.get(0).getEncoded())) {
                throw new GeneralSecurityException(
                    "Signed APK failed local signature verification");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Could not publish verified signed APK");
            }
            return new Result(
                    sha256(certificates.get(0).getEncoded()),
                    verification.isVerifiedUsingV1Scheme(),
                    verification.isVerifiedUsingV2Scheme(),
                    verification.isVerifiedUsingV3Scheme());
        } catch (ApkFormatException malformed) {
            throw new IOException("Cannot sign an invalid Android APK", malformed);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                // Temporary files are in the app's private cache. Caller should
                // retry cleanup before another export.
                System.err.println("Could not delete incomplete signed APK");
            }
        }
    }

    private static String sha256(byte[] data) throws GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(Character.forDigit((b >>> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }
}
