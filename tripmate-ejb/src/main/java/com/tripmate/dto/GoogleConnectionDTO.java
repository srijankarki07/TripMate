package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * What the application knows about one reader's Google Calendar connection.
 *
 * <p>Never carries the refresh token, in either direction. That value exists
 * between the callback that receives it and the service that stores it, and
 * putting it on a DTO would be putting it one accident away from a page, a log
 * line or a remote call's wire.
 */
public class GoogleConnectionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** False when this deployment has no Google credentials, whatever the reader does. */
    private boolean available;

    private boolean connected;

    /** The Google account that granted access, or null when there is none. */
    private String googleEmail;

    private LocalDateTime connectedAt;

    public GoogleConnectionDTO() {
    }

    public GoogleConnectionDTO(boolean available, boolean connected,
            String googleEmail, LocalDateTime connectedAt) {
        this.available = available;
        this.connected = connected;
        this.googleEmail = googleEmail;
        this.connectedAt = connectedAt;
    }

    /** @return true when the application has been given what it needs to offer this */
    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /** @return the email of the connected account, or null */
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
}
