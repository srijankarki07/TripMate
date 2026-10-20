package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;
import com.tripmate.remote.UserServiceRemote;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Account creation.
 *
 * <p>On success the new user is signed in immediately, since having just
 * supplied the credentials there is nothing gained by asking for them again.
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
    private CurrentUser currentUser;

    private String fullName;
    private String email;
    private String phone;
    private String password;
    private String confirmPassword;
    private String error;

    /**
     * @return a redirect to the dashboard on success, or null to redisplay the
     *         form with an error
     */
    public String register() {
        error = null;

        if (isBlank(fullName) || isBlank(email) || isBlank(password)) {
            error = "Name, email and password are all required.";
            return null;
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            error = "Choose a password of at least " + MINIMUM_PASSWORD_LENGTH + " characters.";
            return null;
        }
        if (!password.equals(confirmPassword)) {
            error = "The two passwords do not match.";
            return null;
        }

        try {
            UserDTO created = userService.register(
                    new UserDTO(null, fullName.trim(), email.trim(),
                            isBlank(phone) ? null : phone.trim()),
                    password);
            currentUser.login(created);
            return "/dashboard.xhtml?faces-redirect=true";

        } catch (RuntimeException ex) {
            // The service rejects a duplicate address before touching the
            // database, so this is a message worth showing rather than an
            // unexpected failure.
            error = rootMessage(ex);
            return null;
        }
    }

    /**
     * Unwraps to the cause. The container reports a failure from a session bean
     * as an EJBException whose own message is null, which would hide the one
     * line that says what was wrong with the input.
     */
    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? "Could not create the account." : message;
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

    public String getError() {
        return error;
    }
}
