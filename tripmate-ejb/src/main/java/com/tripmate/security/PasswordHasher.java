package com.tripmate.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing for registration and login.
 *
 * <p>Uses PBKDF2-HMAC-SHA256, which ships with the JDK, so this needs no
 * third-party dependency. PBKDF2 is deliberately slow and salted: a plain
 * SHA-256 digest of a password is reversible by lookup table in milliseconds,
 * and an unsalted one lets an attacker see that two users share a password.
 *
 * <h2>Stored format</h2>
 *
 * <pre>
 * pbkdf2_sha256$&lt;iterations&gt;$&lt;salt-base64&gt;$&lt;hash-base64&gt;
 * </pre>
 *
 * <p>The parameters travel with the hash. That is what makes the iteration
 * count upgradable: raising {@link #ITERATIONS} does not invalidate existing
 * rows, because verification reads the count each hash was created with rather
 * than the current constant.
 *
 * <p>This is the format already present in {@code database/seed.sql}, so the
 * seeded users can log in without any migration. Changing it here would break
 * them.
 */
public final class PasswordHasher {

    /** Identifier stored in the first field, so the scheme can be versioned. */
    private static final String SCHEME = "pbkdf2_sha256";

    /** Deliberately high: this is the work factor an attacker must also pay. */
    private static final int ITERATIONS = 210_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A well-formed hash of a value nobody can supply, used to keep the failure
     * path of {@code login} as slow as the success path. See
     * {@code UserService.login}.
     */
    private static final String DUMMY_HASH = hash("timing-equalisation-placeholder");

    private PasswordHasher() {
        // Static utility; not instantiable.
    }

    /**
     * Hashes a password with a fresh random salt.
     *
     * @param password the plaintext, never stored
     * @return the encoded hash, safe to persist
     * @throws IllegalArgumentException if the password is null or empty
     */
    public static String hash(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] derived = derive(password, salt, ITERATIONS, KEY_BITS);

        return SCHEME
                + "$" + ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * Checks a password against a stored hash.
     *
     * <p>Returns false rather than throwing for a malformed or absent hash, so
     * that a corrupt row is a failed login instead of an error page. The
     * comparison uses {@link MessageDigest#isEqual}, which does not stop at the
     * first differing byte; a loop with {@code equals} would leak how much of
     * the hash was guessed correctly through its running time.
     *
     * @param password   the plaintext to check
     * @param storedHash the encoded hash from the database
     * @return true only if the password matches
     */
    public static boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }

        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (expected.length == 0 || salt.length == 0) {
                return false;
            }

            // Key length comes from the stored hash, not from KEY_BITS, so a
            // longer digest produced by an older build still verifies.
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);

        } catch (IllegalArgumentException malformed) {
            // Bad base64 or a non-numeric iteration count.
            return false;
        }
    }

    /**
     * Burns the same work as a real verification against a value that cannot
     * match.
     *
     * <p>Called when an email is not found. Without it, a missing account
     * returns in microseconds while a wrong password takes a fifth of a second,
     * and that difference tells an attacker which email addresses are
     * registered. The result is discarded; only the time spent matters.
     */
    public static void burnEquivalentWork(String password) {
        verify(password, DUMMY_HASH);
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            // Both are configuration failures rather than user errors: the
            // algorithm ships with every JDK 8+ and the spec above is valid.
            throw new IllegalStateException("PBKDF2 unavailable: " + ex.getMessage(), ex);
        } finally {
            // Clears the copy of the password held by the spec.
            spec.clearPassword();
        }
    }
}
