package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One user's connection to their Google Calendar.
 *
 * <p>Maps to {@code google_calendar_links}. At most one per user, so the user's
 * own id is the primary key and there is no separate one.
 *
 * <h2>The refresh token is stored encrypted</h2>
 *
 * <p>{@link com.tripmate.security.TokenCipher} carries the argument. In short:
 * this is the only credential the application stores that has to be read back
 * <em>and</em> grants access to something outside this database, so it is the
 * one that needs more than the database to be useful to a thief. The field holds
 * ciphertext, and nothing but that class and the service around it should ever
 * touch it.
 *
 * <p>Which also means a lost key is a lost connection. {@code decrypt} throws
 * rather than returning null in that case, because null reads as "never
 * connected" and would quietly drop the reader's calendar instead of telling
 * somebody the key changed.
 */
@Entity
@Table(name = "google_calendar_links")
public class GoogleCalendarLink implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Ciphertext, in the form {@code v1:<iv>:<sealed>}. Never the token itself. */
    @Column(name = "refresh_token", nullable = false, length = 1024)
    private String refreshToken;

    /** Which of the reader's calendars to write into. */
    @Column(name = "calendar_id", nullable = false, length = 255)
    private String calendarId;

    /** Which Google account granted it, so the reader can see rather than guess. */
    @Column(name = "google_email", length = 255)
    private String googleEmail;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @PrePersist
    void prePersist() {
        if (connectedAt == null) {
            connectedAt = LocalDateTime.now();
        }
        if (calendarId == null || calendarId.isBlank()) {
            calendarId = "primary";
        }
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(String calendarId) {
        this.calendarId = calendarId;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    public void setGoogleEmail(String googleEmail) {
        this.googleEmail = googleEmail;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(LocalDateTime connectedAt) {
        this.connectedAt = connectedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GoogleCalendarLink link)) {
            return false;
        }
        return userId != null && userId.equals(link.userId);
    }

    @Override
    public int hashCode() {
        return GoogleCalendarLink.class.hashCode(); // constant; see User.hashCode
    }

    /**
     * Deliberately says nothing but the key. This string reaches log files, and
     * both of the interesting fields here are things a log should not carry: one
     * is an encrypted credential and the other is somebody's email address.
     */
    @Override
    public String toString() {
        return "GoogleCalendarLink{userId=" + userId + "}";
    }
}
