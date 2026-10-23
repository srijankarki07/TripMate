package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Account creation.
 *
 * <h2>The account is created, not opened</h2>
 *
 * <p>The previous version signed the new user in on the spot, on the reasoning
 * that they had just supplied the credentials and there was nothing gained by
 * asking again. That was true while the credentials were the only thing being
 * established. Now they are not: the address also has to answer, because a
 * mistyped one means an account nobody can reach and notifications that go
 * nowhere, and the only way to know is to send something to it and see.
 *
 * <p>So registering ends on the verification page with a code in flight, and
 * the sign-in happens there, once the address has answered. The credentials
 * are not asked for twice; they are already in the session.
 */
@Named
@RequestScoped
public class RegisterBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Short enough to type, long enough not to be the weak link. */
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    @EJB
    private UserServiceRemote userService;

    @Inject
    private PendingVerification pendingVerification;

    private String fullName;
    private String email;
    private String phone;
    private String password;
    private String confirmPassword;

    /**
     * Creates the account and sends the code that confirms its address.
     *
     * @return a redirect to the verification page on success, or null to
     *         redisplay the form with an error
     */
    public String register() {

        if (isBlank(fullName) || isBlank(email) || isBlank(password)) {
            Toasts.error("Name, email and password are all required.");
            return null;
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            Toasts.error("Choose a password of at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
            return null;
        }
        if (!password.equals(confirmPassword)) {
            Toasts.error("The two passwords do not match.");
            return null;
        }

        try {
            UserDTO created = userService.register(
                    new UserDTO(null, fullName.trim(), email.trim(),
                            isBlank(phone) ? null : phone.trim()),
                    password);

            // Registered, and deliberately not signed in. The account is held
            // in the session so the verification page knows which one it is
            // confirming, and the code has already been sent by the service,
            // which is where registering happens.
            pendingVerification.remember(created);
            Toasts.info("We have sent a six-digit code to " + created.getEmail() + ".");
            return "/verify-email.xhtml?faces-redirect=true";

        } catch (RuntimeException ex) {
            // The service rejects a duplicate address before touching the
            // database, so this is a message worth showing rather than an
            // unexpected failure.
            Toasts.error(Messages.describe(ex));
            return null;
        }
    }
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
