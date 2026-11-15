package org.love2d.android;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Offline per-game signing identity: generate, inspect, back up and restore
 * an encrypted, exportable PKCS#12 key file.
 *
 * No account, network, Android UI or persistent password storage. This code
 * must be called on a background thread. The caller MUST choose a private
 * directory belonging to CodeLove and must safeguard the backup password.
 * Without both the original key file and its password, Android cannot accept
 * an APK update signed with the original identity.
 *
 * Bouncy Castle is used for X.509 certificate creation, not as a globally
 * installed provider. The resulting file uses the platform's PKCS#12 API,
 * which OfflineApkSigner already consumes.
 */
final class GameSigningKeyStore {
    static final String KEY_ALIAS = "game-key";
    private static final int RSA_BITS = 3072;
    private static final int MIN_PASSWORD_CHARS = 12;
    private static final long CERT_VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000;

    static final class Identity {
        final String sha256;
        final String alias;
        final Date expiresAt;

        Identity(String sha256, String alias, Date expiresAt) {
            this.sha256 = sha256;
            this.alias = alias;
            this.expiresAt = new Date(expiresAt.getTime());
        }
    }

    private GameSigningKeyStore() {}

    /** Generate a new signing identity. Never replace an existing identity. */
    static synchronized Identity create(File destination, char[] password)
            throws IOException, GeneralSecurityException {
        requirePassword(password, true);
        requireNewDestination(destination);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_BITS, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = newCertificate(pair);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try {
            store.load(null, password);
            store.setKeyEntry(KEY_ALIAS, pair.getPrivate(), password,
                    new Certificate[]{certificate});
        } catch (java.security.cert.CertificateException error) {
            throw new GeneralSecurityException("Cannot initialize PKCS12 key", error);
        }
        File temp = createTempNextTo(destination);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                store.store(out, password);
                out.getFD().sync();
            }
            Identity identity = inspect(temp, password);
            if (!identity.sha256.equals(fingerprint(certificate))) {
                throw new GeneralSecurityException("Generated identity did not round-trip");
            }
            publishNewFile(temp, destination);
            return identity;
        } catch (java.security.cert.CertificateException error) {
            throw new GeneralSecurityException("Cannot save PKCS12 key", error);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    /** Confirm that a password unlocks the private key and certificate. */
    static Identity inspect(File file, char[] password)
            throws IOException, GeneralSecurityException {
        requirePassword(password, false);
        if (file == null || !file.isFile() || file.length() <= 0
                || file.length() > 1024 * 1024) {
            throw new IOException("Invalid signing key file");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(file)) {
            store.load(in, password);
        } catch (java.security.cert.CertificateException error) {
            throw new GeneralSecurityException("Invalid PKCS12 key or password", error);
        }
        if (!store.isKeyEntry(KEY_ALIAS)) {
            throw new GeneralSecurityException("Signing key alias is missing");
        }
        Key key = store.getKey(KEY_ALIAS, password);
        Certificate certificate = store.getCertificate(KEY_ALIAS);
        if (!(key instanceof PrivateKey) || !(certificate instanceof X509Certificate)) {
            throw new GeneralSecurityException("PKCS12 does not contain an X.509 signing key");
        }
        X509Certificate x509 = (X509Certificate) certificate;
        x509.checkValidity();
        // Check that the private key belongs to the certificate, not merely
        // that both were successfully parsed.
        try {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature sign = Signature.getInstance("SHA256withRSA");
            sign.initSign((PrivateKey) key);
            sign.update(challenge);
            byte[] signature = sign.sign();
            Signature verify = Signature.getInstance("SHA256withRSA");
            verify.initVerify(x509.getPublicKey());
            verify.update(challenge);
            if (!verify.verify(signature)) {
                throw new GeneralSecurityException("Private key does not match certificate");
            }
        } catch (java.security.SignatureException error) {
            throw new GeneralSecurityException("Invalid signing key", error);
        }
        return new Identity(fingerprint(x509), KEY_ALIAS, x509.getNotAfter());
    }

    /** Copy an encrypted backup, retaining ownership of the output stream. */
    static Identity backup(File storedKey, char[] password, OutputStream backup)
            throws IOException, GeneralSecurityException {
        if (backup == null) throw new IOException("Missing backup destination");
        Identity identity = inspect(storedKey, password);
        try (FileInputStream in = new FileInputStream(storedKey)) {
            transfer(in, backup);
            backup.flush();
        }
        return identity;
    }

    /**
     * Restore a user-owned PKCS#12 backup into a NEW app-private file.
     * The supplied stream is not closed. Existing identities are never
     * replaced because doing so could permanently break game updates.
     */
    static synchronized Identity restore(InputStream backup, File destination, char[] password)
            throws IOException, GeneralSecurityException {
        if (backup == null) throw new IOException("Missing backup input");
        requirePassword(password, false);
        requireNewDestination(destination);
        File temp = createTempNextTo(destination);
        try {
            long bytes = 0;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = backup.read(buffer)) != -1) {
                    if (n == 0) continue;
                    bytes += n;
                    if (bytes > 1024 * 1024) {
                        throw new IOException("PKCS12 backup is too large");
                    }
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            Identity identity = inspect(temp, password);
            publishNewFile(temp, destination);
            return identity;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static X509Certificate newCertificate(KeyPair pair)
            throws GeneralSecurityException {
        try {
            SecureRandom random = new SecureRandom();
            byte[] serialBytes = new byte[20];
            random.nextBytes(serialBytes);
            // Positive serial, unique per game, no personal identification.
            serialBytes[0] &= 0x7f;
            BigInteger serial = new BigInteger(1, serialBytes);
            if (serial.signum() == 0) serial = BigInteger.ONE;
            long now = System.currentTimeMillis();
            Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
            Date notAfter = new Date(now + CERT_VALIDITY_MS);
            X500Name issuer = new X500Name("CN=CodeLove Game Signing");
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, serial, notBefore, notAfter, issuer, pair.getPublic());
            BouncyCastleProvider provider = new BouncyCastleProvider();
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(provider).build(pair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(provider).getCertificate(builder.build(signer));
            certificate.verify(pair.getPublic());
            certificate.checkValidity();
            return certificate;
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not create game certificate", error);
        }
    }

    private static void requirePassword(char[] password, boolean creating)
            throws GeneralSecurityException {
        if (password == null || password.length == 0
                || (creating && password.length < MIN_PASSWORD_CHARS)) {
            throw new GeneralSecurityException(
                    creating ? "Signing key password requires at least 12 characters"
                            : "Signing key password is required");
        }
    }

    private static void requireNewDestination(File destination) throws IOException {
        if (destination == null || destination.exists()) {
            throw new IOException("Refusing to overwrite signing identity");
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Private signing-key directory must already exist");
        }
    }

    private static File createTempNextTo(File destination) throws IOException {
        return File.createTempFile(".codelove-key-", ".p12",
                destination.getAbsoluteFile().getParentFile());
    }

    private static void publishNewFile(File temp, File destination) throws IOException {
        // The temporary key was fully written and fsynced in the same private
        // directory. Rename publishes atomically, avoiding a half-written key
        // after abrupt process death. create/restore are synchronized and
        // neither operation may replace an existing identity.
        if (destination.exists() || !temp.renameTo(destination)) {
            throw new IOException("Could not safely publish new signing identity");
        }
    }

    private static void transfer(InputStream input, OutputStream output)
            throws IOException {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > 0) output.write(buffer, 0, n);
        }
    }

    private static String fingerprint(X509Certificate certificate)
            throws GeneralSecurityException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(b & 0x0f, 16));
            }
            return out.toString();
        } catch (java.security.cert.CertificateEncodingException error) {
            throw new GeneralSecurityException("Cannot fingerprint signing certificate", error);
        }
    }
}
