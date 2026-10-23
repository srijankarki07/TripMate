package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;

import com.tripmate.dto.UserDTO;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

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
    private BigDecimal totalPaid = BigDecimal.ZERO;
    private BigDecimal totalOwed = BigDecimal.ZERO;
    private BigDecimal totalRepaidOut = BigDecimal.ZERO;
    private BigDecimal totalReceived = BigDecimal.ZERO;
    private long tripCount;

    @PostConstruct
    void load() {
        UserDTO user = currentUser.getUser();
        if (user != null) {
            fullName = user.getFullName();
            email = user.getEmail();
            phone = user.getPhone();
        }
        Long userId = currentUser.getUserId();
        if (currentUser.isLoggedIn()) {
            totalPaid = reportService.getTotalPaidByUser(userId, userId);
            totalOwed = reportService.getTotalOwedByUser(userId, userId);
            // The repayments, so the net figure below is what is actually still
            // to move rather than what would have been owed if nobody had paid
            // anybody back. Recording a repayment used to leave this page
            // exactly as it was, which is the fault this fixes.
            totalRepaidOut = reportService.getTotalRepaidOutByUser(userId, userId);
            totalReceived = reportService.getTotalReceivedByUser(userId, userId);
            tripCount = reportService.getTripCountForUser(userId, userId);
        }
    }

    public void saveProfile() {

        if (fullName == null || fullName.isBlank()) {
            Toasts.error("Your name cannot be empty.");
            return;
        }
        if (email == null || email.isBlank()) {
            Toasts.error("Your email cannot be empty. It is how you sign in.");
            return;
        }

        UserDTO update = new UserDTO(currentUser.getUserId(), fullName,
                email, phone == null || phone.isBlank() ? null : phone);
        try {
            UserDTO saved = userService.updateUser(update);
            // The session holds a copy, so it has to be told, or the header
            // would keep showing the old name until the next sign-in.
            currentUser.refresh(saved);
            Toasts.success("Profile saved.");
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    public void changePassword() {

        UserDTO user = currentUser.getUser();
        if (user == null) {
            Toasts.error("You are not signed in.");
            return;
        }
        if (currentPassword == null || currentPassword.isEmpty()) {
            Toasts.error("Enter your current password.");
            return;
        }
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            Toasts.error("Choose a new password of at least "
                    + MINIMUM_PASSWORD_LENGTH + " characters.");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            Toasts.error("The two new passwords do not match.");
            return;
        }
        if (newPassword.equals(currentPassword)) {
            Toasts.error("The new password is the same as the current one.");
            return;
        }

        // Re-authenticates rather than trusting the session. A session can be
        // left open on a shared machine, and changing the password is the one
        // action that could lock the owner out of their own account.
        if (userService.login(user.getEmail(), currentPassword) == null) {
            Toasts.error("That is not your current password.");
            return;
        }

        try {
            userService.changePassword(user.getUserId(), currentPassword, newPassword,
                    currentUser.getUserId());
            Toasts.success("Password changed.");
            currentPassword = null;
            newPassword = null;
            confirmPassword = null;
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
    }

    // ------------------------------------------------------------------
    // Figures shown alongside the form
    // ------------------------------------------------------------------

    /**
     * What is still to move, across every trip.
     *
     * <p>Positive means the user is still owed it. The repayments already
     * recorded are counted, because a repayment is money that has changed
     * hands: leaving it out made this the one figure on the page that did not
     * move when somebody settled up.
     *
     * @return paid minus share, plus repaid out, minus received
     */
    public BigDecimal getNetPosition() {
        return totalPaid.subtract(totalOwed)
                .add(totalRepaidOut)
                .subtract(totalReceived);
    }

    /** @return true when any repayment has moved this user's figure */
    public boolean isTouchedByRepayments() {
        return totalRepaidOut.signum() != 0 || totalReceived.signum() != 0;
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

    public BigDecimal getTotalRepaidOut() {
        return totalRepaidOut;
    }

    public BigDecimal getTotalReceived() {
        return totalReceived;
    }

    public long getTripCount() {
        return tripCount;
    }

    
}
