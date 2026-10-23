package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

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
 * One outstanding password reset.
 *
 * <h2>What is stored, and what is not</h2>
 *
 * Only the SHA-256 of the token that was emailed, never the token itself. A
 * password reset token is a bearer credential: whoever holds it can set the
 * password. Storing it in the clear would mean a database backup, a log line or
 * a read-replica leak hands over the ability to take over any account with an
 * outstanding request. A hash is enough to check a presented token and useless
 * to anyone who steals it.
 *
 * <p>SHA-256 rather than the PBKDF2 used for passwords, and deliberately. A
 * password is low-entropy and needs a slow hash to survive being guessed; this
 * token is 256 bits of randomness, so there is nothing to guess and a fast hash
 * is the right tool. Slowing it down would only make every reset request more
 * expensive to serve.
 *
 * <h2>Why a spent token is marked rather than deleted</h2>
 *
 * {@code consumedAt} distinguishes "this token was already used" from "this
 * token never existed". Both are answered identically to the person holding the
 * link, because telling them apart helps nobody who is entitled to know. The
 * difference is worth having in the record.
 */
@Entity
@Table(name = "password_resets")
public class PasswordReset implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "passwordResetSeq")
    @SequenceGenerator(name = "passwordResetSeq", sequenceName = "password_resets_reset_id_seq",
            allocationSize = 1)
    @Column(name = "reset_id", nullable = false)
    private Long resetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The SHA-256 of the token, as lower-case hex. Never the token. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when the token was spent. Null while it is still usable. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /**
     * Where the request came from, for a rate limit and for a record.
     *
     * <p>45 characters is the maximum length of a textual IPv6 address including
     * an embedded IPv4 tail.
     */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    @PrePersist
    void prePersist() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }

    /**
     * @return whether this token can still be used
     *
     * <p>Both conditions matter: an unspent token that has expired must be
     * refused, and so must a spent one that has not.
     */
    public boolean isUsable(LocalDateTime now) {
        return consumedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Long getResetId() {
        return resetId;
    }

    public void setResetId(Long resetId) {
        this.resetId = resetId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
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
        if (!(other instanceof PasswordReset reset) || resetId == null) {
            return false;
        }
        return resetId.equals(reset.resetId);
    }

    @Override
    public int hashCode() {
        return PasswordReset.class.hashCode();
    }

    @Override
    public String toString() {
        // No token hash, and no user id beyond the surrogate key.
        return "PasswordReset{resetId=" + resetId + "}";
    }
}
