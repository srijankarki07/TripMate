package com.tripmate.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints and checks the bearer tokens the application hands out.
 *
 * <h2>Not the same tool as {@link PasswordHasher}, and that is the point</h2>
 *
 * A password is chosen by a person, so it is low-entropy and guessable, and the
 * defence is to make each guess expensive: PBKDF2 with 210,000 iterations. A
 * token minted here is 256 bits from {@link SecureRandom}, so there is nothing
 * to guess and nothing for a slow hash to protect. What matters instead is that
 * the stored form is useless to a thief, and SHA-256 gives that in one pass.
 *
 * <p>Using PBKDF2 here would not be safer, it would just make every reset link
 * expensive to check for no gain. Using SHA-256 for passwords would be a
 * serious mistake; the two cases look similar and are not.
 *
 * <h2>Constant-time comparison</h2>
 *
 * {@link #matches} compares with {@link MessageDigest#isEqual}, which does not
 * stop at the first differing byte. An early-exit comparison leaks, through
 * timing, how much of a guess was correct, which is enough to recover a token
 * one byte at a time.
 */
public final class TokenHasher {

    /** 32 bytes is 256 bits, which is the current floor for a bearer token. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    /**
     * Mints a new token.
     *
     * <p>URL-safe base64 without padding, because the value is pasted into a
     * link and padding's trailing {@code =} is the character most likely to be
     * mangled by a mail client, a chat application or a person copying it by
     * hand.
     *
     * @return 43 characters of URL-safe base64
     */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param token a token as it was issued
     * @return its SHA-256 as lower-case hex, which is what gets stored
     */
    public static String hash(String token) {
        if (token == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform. Reaching here means
            // the runtime is not one, and continuing without it would be worse
            // than failing.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Checks a presented token against a stored hash, in constant time.
     *
     * @param token      the value from the link
     * @param storedHash the hash kept in the database, or null
     * @return true only when they correspond
     */
    public static boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null) {
            return false;
        }
        String presented = hash(token);
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
