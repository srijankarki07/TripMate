package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.ejb.PasswordResetService;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;

/**
 * The page a reset link opens.
 *
 * <p>The token arrives as a view parameter rather than being exchanged for a
 * session flag, so the link works from any browser, survives being opened on a
 * phone after being requested on a laptop, and does not depend on the session
 * that asked for it. That is what a reset link is for.
 *
 * <p>The form is only shown when the token is usable. An expired or already
 * spent link gets a sentence saying so and a way to ask for another, rather
 * than a password form that would refuse on submit.
 */
@Named
@ViewScoped
public class ResetPasswordBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    @EJB
    private PasswordResetService passwordResetService;

    private String token;
    private String newPassword;
    private String confirmPassword;
    private String greeting;
    private boolean tokenChecked;

    /** Looks the token up so the page can greet the holder, or refuse them. */
    public void load() {
        greeting = passwordResetService.describeToken(token);
        tokenChecked = true;
    }

    /**
     * Sets the new password and sends the person to sign in.
     *
     * @return a redirect to sign-in, or null to redisplay with the reason
     */
    public String save() {
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            Toasts.error("Choose a password of at least " + MINIMUM_PASSWORD_LENGTH
                    + " characters.");
            return null;
        }
        if (!newPassword.equals(confirmPassword)) {
            Toasts.error("The two passwords do not match.");
            return null;
        }

        try {
            passwordResetService.consumeReset(token, newPassword);
            Toasts.success("Your password has been changed. Sign in with the new one.");
            return "/login.xhtml?faces-redirect=true";
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
            // The token may have expired or been spent between the page loading
            // and the form being submitted, so the state is re-read rather than
            // assumed to be what it was.
            load();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public String getGreeting() {
        return greeting;
    }

    /** @return whether the page has decided yet whether the link is usable */
    public boolean isTokenChecked() {
        return tokenChecked;
    }

    /** @return whether the form should be offered */
    public boolean isUsable() {
        return greeting != null;
    }
}
