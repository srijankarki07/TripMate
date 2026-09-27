package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;

import com.tripmate.dto.UserDTO;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.UserServiceRemote;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The signed-in user's own profile.
 *
 * <p>Two separate forms, because they are two separate operations: changing a
 * name does not need a password, and changing a password should not be possible
 * from a session someone walked away from. The password form therefore asks for
 * the current password even though the user is already signed in.
 */
@Named
@ViewScoped
public class ProfileBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    @EJB
    private UserServiceRemote userService;

    @EJB
    private ReportServiceRemote reportService;

    @Inject
    private CurrentUser currentUser;

    private String fullName;
    private String email;
    private String phone;

    private String currentPassword;
    private String newPassword;
    private String confirmPassword;

    private String error;
    private String message;
    private String passwordError;
    private String passwordMessage;

    private BigDecimal totalPaid = BigDecimal.ZERO;
    private BigDecimal totalOwed = BigDecimal.ZERO;
    private long tripCount;

    @PostConstruct
    void load() {
        UserDTO user = currentUser.getUser();
        if (user != null) {
            fullName = user.getFullName();
            email = user.getEmail();
            phone = user.getPhone();
        }
        if (currentUser.isLoggedIn()) {
            totalPaid = reportService.getTotalPaidByUser(currentUser.getUserId());
            totalOwed = reportService.getTotalOwedByUser(currentUser.getUserId());
            tripCount = reportService.getTripCountForUser(currentUser.getUserId());
        }
    }

    public void saveProfile() {
        error = null;
        message = null;

        if (fullName == null || fullName.isBlank()) {
            error = "Your name cannot be empty.";
            return;
        }
        if (email == null || email.isBlank()) {
            error = "Your email cannot be empty. It is how you sign in.";
            return;
        }

        UserDTO update = new UserDTO(currentUser.getUserId(), fullName,
                email, phone == null || phone.isBlank() ? null : phone);
        try {
            UserDTO saved = userService.updateUser(update);
            // The session holds a copy, so it has to be told, or the header
            // would keep showing the old name until the next sign-in.
            currentUser.refresh(saved);
            message = "Profile saved.";
        } catch (RuntimeException ex) {
            error = rootMessage(ex);
        }
    }

    public void changePassword() {
        passwordError = null;
        passwordMessage = null;

        UserDTO user = currentUser.getUser();
        if (user == null) {
            passwordError = "You are not signed in.";
            return;
        }
        if (currentPassword == null || currentPassword.isEmpty()) {
            passwordError = "Enter your current password.";
            return;
        }
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            passwordError = "Choose a new password of at least "
                    + MINIMUM_PASSWORD_LENGTH + " characters.";
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            passwordError = "The two new passwords do not match.";
            return;
        }
        if (newPassword.equals(currentPassword)) {
            passwordError = "The new password is the same as the current one.";
            return;
        }

        // Re-authenticates rather than trusting the session. A session can be
        // left open on a shared machine, and changing the password is the one
        // action that could lock the owner out of their own account.
        if (userService.login(user.getEmail(), currentPassword) == null) {
            passwordError = "That is not your current password.";
            return;
        }

        try {
            userService.changePassword(user.getUserId(), newPassword);
            passwordMessage = "Password changed.";
            currentPassword = null;
            newPassword = null;
            confirmPassword = null;
        } catch (RuntimeException ex) {
            passwordError = rootMessage(ex);
        }
    }

    // ------------------------------------------------------------------
    // Figures shown alongside the form
    // ------------------------------------------------------------------

    public BigDecimal getNetPosition() {
        return totalPaid.subtract(totalOwed);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

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

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
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

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }

    public BigDecimal getTotalOwed() {
        return totalOwed;
    }

    public long getTripCount() {
        return tripCount;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPasswordError() {
        return passwordError;
    }

    public String getPasswordMessage() {
        return passwordMessage;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? "Something went wrong." : message;
    }
}
