package com.tripmate.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the one secret the application has to be able to read back.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A password is hashed. A password reset token is hashed. A calendar feed
 * token is not, and the argument for that is written next to it: the itinerary
 * it guards is in the same database, so a hash would protect nothing and cost
 * the ability to show the address again.
 *
 * <p>A Google refresh token is neither case. It has to be read back, because the
 * application presents it to Google, so hashing is not available. And it does
 * not guard something in this database: it grants access to the reader's entire
 * Google Calendar, which a dump of these tables would otherwise hand over along
 * with everything else. Storing it as it arrives would make a backup, a replica
 * or a query log worth more than it should be.
 *
 * <p>So it is encrypted with a key that lives in the container's configuration
 * and never in the database. Somebody who reads the database and not the
 * server's configuration has ciphertext; somebody who has both was already able
 * to read the application's memory and has nothing left to gain from this.
 *
 * <h2>The format</h2>
 *
 * <p>{@code v1:<base64 iv>:<base64 ciphertext>}. The version is there so that a
 * future change of algorithm can tell its own output from this one's rather than
 * guessing, and the initialisation vector is stored beside the ciphertext
 * because AES-GCM requires a fresh one per encryption and it is not secret.
 *
 * <p>GCM rather than a plain mode because it authenticates as well as encrypts:
 * a token that has been altered in the database fails to decrypt rather than
 * decrypting to something else and being sent to Google.
 */
public final class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Prefix identifying this format. */
    private static final String VERSION = "v1";

    /** AES-256. */
    private static final int KEY_BYTES = 32;

    /** GCM's recommended tag length. */
    private static final int TAG_BITS = 128;

    /** 96 bits, the size GCM is specified for and the size it is fastest at. */
    private static final int IV_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenCipher() {
        // Static helpers; not instantiable.
    }

    /**
     * @return a new key, base64, for an operator to put in the container's
     *         configuration
     */
    public static String newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Encrypts a value that will have to be read back.
     *
     * @param plaintext the value
     * @param keyBase64 the key, base64, from the deployment's configuration
     * @return the storable form, never null
     * @throws IllegalStateException when there is no usable key, because the
     *                               alternative is storing the value in the
     *                               clear while looking like it did not
     */
    public static String encrypt(String plaintext, String keyBase64) {
        if (plaintext == null) {
            return null;
        }
        SecretKey key = keyFrom(keyBase64);

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION
                    + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(sealed);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("could not encrypt a stored credential", failed);
        }
    }

    /**
     * Decrypts a value written by {@link #encrypt}.
     *
     * @param stored    the stored form
     * @param keyBase64 the same key it was written with
     * @return the original value, or null when there was nothing stored
     * @throws IllegalStateException when the value cannot be read, which means
     *                               the key has changed or the row has been
     *                               altered. Both need a person, and returning
     *                               null would look like "never connected" and
     *                               silently drop the reader's connection.
     */
    public static String decrypt(String stored, String keyBase64) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("stored credential is not in a form this can read");
        }

        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] sealed = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyFrom(keyBase64), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failed) {
            throw new IllegalStateException(
                    "could not read a stored credential: the key has changed, or the row has", failed);
        }
    }

    /**
     * Whether a key is configured well enough to use.
     *
     * <p>Asked before anything tries to store a connection, so the reader is
     * told the feature is unavailable rather than finding out when their
     * calendar stops updating.
     *
     * @param keyBase64 the key from the configuration, possibly null
     * @return true when it is present and the right size
     */
    public static boolean isUsable(String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(keyBase64.trim()).length == KEY_BYTES;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private static SecretKey keyFrom(String keyBase64) {
        if (!isUsable(keyBase64)) {
            // Deliberately fatal. Falling back to storing the value unencrypted
            // would produce a connection that works and a database that leaks,
            // which is the worst of the two outcomes and the harder to notice.
            throw new IllegalStateException(
                    "no usable credential key is configured; see tripmate.credentialsKey");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(keyBase64.trim()), "AES");
    }
}
