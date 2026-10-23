package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * One six-digit code sent to confirm an email address.
 *
 * <h2>Not a {@link PasswordReset} with a shorter token</h2>
 *
 * The shape is deliberately the same and the cryptography is deliberately not.
 * A reset token is 256 bits from {@code SecureRandom}, so it is stored as a
 * SHA-256 digest: there is nothing to guess, and a fast hash is enough to make
 * a stolen copy of the table useless. A code here is six digits, which is one
 * million possibilities and guessable in minutes against a fast hash. So this
 * is stored under the same PBKDF2 used for passwords, and a copy of this table
 * costs an attacker a tenth of a second per candidate rather than a
 * microsecond.
 *
 * <p>See {@code database/schema.sql} for the same reasoning stated where the
 * column is declared.
 *
 * <h2>Two limits, and they do different jobs</h2>
 *
 * {@code expiresAt} bounds how long a code is worth anything, so a message left
 * in an inbox is not a standing key. {@code attempts} bounds how many guesses
 * one code will accept, which is what makes a million possibilities into five.
 * Neither is sufficient alone: without the expiry, a code found later is still
 * live; without the attempt limit, an attacker with the page open simply counts
 * to a million.
 *
 * <h2>Why rows are marked rather than deleted</h2>
 *
 * As with a reset: a spent code and a code that never existed are answered the
 * same way to the person holding it, and the difference is worth having in the
 * record. It also means an account's verification history is readable, which is
 * how "it says my code is wrong" becomes an answerable question.
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerification implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "emailVerificationSeq")
    @SequenceGenerator(name = "emailVerificationSeq",
            sequenceName = "email_verifications_verification_id_seq", allocationSize = 1)
    @Column(name = "verification_id", nullable = false)
    private Long verificationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The PBKDF2 digest of the code, in the same format as a password hash. */
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when the code was used or given up on. Null while it is still live. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /** Wrong guesses so far. The code is spent when this reaches the cap. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Where the request came from, for the rate limit and for the record. */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    @PrePersist
    void prePersist() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }

    /**
     * @return whether this code can still be presented
     *
     * <p>Both conditions matter: an unspent code that has expired must be
     * refused, and so must a spent one that has not run out yet.
     *
     * <p>Whether the account has already been confirmed by some other code is
     * not asked here. That belongs to the service, which holds the
     * {@link User} and asks it.
     */
    public boolean isUsable(LocalDateTime now) {
        return consumedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    /**
     * Counts one wrong guess.
     *
     * @return the number of guesses made, including this one
     */
    public int recordFailedAttempt() {
        return ++attempts;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Long getVerificationId() {
        return verificationId;
    }

    public void setVerificationId(Long verificationId) {
        this.verificationId = verificationId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getRequestedIp() {
        return requestedIp;
    }

    public void setRequestedIp(String requestedIp) {
        this.requestedIp = requestedIp;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmailVerification verification)
                || verificationId == null) {
            return false;
        }
        return verificationId.equals(verification.verificationId);
    }

    @Override
    public int hashCode() {
        return EmailVerification.class.hashCode();
    }

    @Override
    public String toString() {
        // No code hash, and no user id beyond the surrogate key.
        return "EmailVerification{verificationId=" + verificationId
                + ", attempts=" + attempts + "}";
    }
}
