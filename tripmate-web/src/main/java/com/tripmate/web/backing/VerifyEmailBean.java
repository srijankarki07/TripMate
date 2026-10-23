package com.tripmate.web.backing;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;

import com.tripmate.dto.UserDTO;
import com.tripmate.ejb.EmailVerificationService;
import com.tripmate.ejb.EmailVerificationService.Outcome;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.web.support.ExternalAddress;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The page where a new account types the code that was emailed to it.
 *
 * <h2>Where the account comes from</h2>
 *
 * <p>The session, never the form. See {@link PendingVerification}: the account
 * being confirmed is the one the person proved they hold, either by registering
 * it or by signing in to it, and this page cannot be pointed at another.
 *
 * <h2>The four ways a code can fail, and why they are separate sentences</h2>
 *
 * <p>Wrong, expired, tried too often, and nothing waiting. They look alike from
 * the outside and call for different next steps, which is the test of whether
 * they should be one message: "wrong" means try again, "expired" means ask for
 * another, "too often" means the code is now dead and asking again is the only
 * way forward, and "nothing waiting" means this page was reached in a state
 * that cannot work at all. Collapsing them into "that code is not valid" is the
 * usual shortcut and it leaves the person with no idea which of the four they
 * are in.
 *
 * <h2>Expiry is recoverable by design</h2>
 *
 * <p>A code that runs out costs a press, not an account. The resend button is
 * always on the page, and the service lets it work immediately once no code is
 * live: the one-minute cooldown is there to stop a live code being replaced in
 * a loop, not to make somebody wait out a code that has already stopped
 * working. Signing in again also works, and lands back here with a fresh code,
 * so closing the tab is not a way to lose the account either.
 */
@Named
@ViewScoped
public class VerifyEmailBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private EmailVerificationService emailVerificationService;

    @EJB
    private UserServiceRemote userService;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private PendingVerification pendingVerification;

    private String code;

    /**
     * Turns away anybody who arrives without an account to confirm.
     *
     * <p>A view action, so it runs on arrival rather than on submit. The state
     * it guards is a session that has expired, or a bookmarked address opened
     * in a fresh browser, and the honest answer to both is the sign-in form
     * rather than a code box that cannot accept anything.
     *
     * @return a redirect to sign-in, or null to render the page
     */
    public String requirePendingAccount() {
        if (!pendingVerification.isPresent()) {
            Toasts.warning("Start by signing in, or creating an account.");
            return "/login.xhtml?faces-redirect=true";
        }
        return null;
    }

    /**
     * Checks the code, and lets the person in when it is right.
     *
     * @return a redirect to the dashboard once confirmed, or null to redisplay
     *         the page with what went wrong
     */
    public String verify() {
        if (!pendingVerification.isPresent()) {
            return requirePendingAccount();
        }
        if (code == null || code.isBlank()) {
            // Answered here rather than by a required field, because the resend
            // button posts this same form and a validation error would stop it
            // sending the code that is missing. See the page.
            Toasts.error("Enter the six-digit code from the email.");
            return null;
        }

        try {
            Outcome outcome = emailVerificationService.verify(
                    pendingVerification.getUserId(), code);
            switch (outcome) {
                case VERIFIED, ALREADY_VERIFIED -> {
                    return signIn();
                }
                case WRONG_CODE -> {
                    int left = emailVerificationService
                            .attemptsRemaining(pendingVerification.getUserId());
                    Toasts.error(left > 0
                            ? "That code is not right. " + left + " tr"
                                    + (left == 1 ? "y" : "ies") + " left."
                            : "That code is not right.");
                }
                case EXPIRED -> Toasts.error(
                        "That code has expired. Send a new one and it will work straight away.");
                case TOO_MANY_ATTEMPTS -> Toasts.error(
                        "That code was tried too many times, so it no longer works. "
                                + "Send a new one.");
                case NOTHING_OUTSTANDING -> Toasts.error(
                        "There is no code waiting for this account. Send a new one.");
            }
        } catch (RuntimeException ex) {
            // A failure the application did not plan for, said in one sentence;
            // the detail goes to the log. See Messages.
            Toasts.error(Messages.describe(ex));
        }
        return null;
    }

    /**
     * Sends a replacement code.
     *
     * <p>Never returns a navigation outcome: whether it worked or not, the
     * person is in the same place, either waiting for a new code or reading why
     * they cannot have one yet.
     */
    public String resend() {
        if (!pendingVerification.isPresent()) {
            return requirePendingAccount();
        }
        try {
            emailVerificationService.resend(pendingVerification.getUserId(),
                    ExternalAddress.clientAddress(request()));
            Toasts.success("A new code is on its way to " + pendingVerification.getEmail()
                    + ". The old one has stopped working.");
        } catch (RuntimeException ex) {
            Toasts.error(Messages.describe(ex));
        }
        return null;
    }

    /**
     * Signs the person in, now that the address has answered.
     *
     * <p>Reads the account back rather than trusting the copy in
     * {@link PendingVerification}, which was taken before the confirmation and
     * so still says the address is unconfirmed. A DTO whose flag disagreed with
     * the database would be a trap for the next page that read it.
     */
    private String signIn() {
        Long userId = pendingVerification.getUserId();
        UserDTO user = userService.getUser(userId);
        pendingVerification.clear();

        if (user == null) {
            // The account went away between the code being sent and being used.
            // Rare, and not a failure to hide: there is nothing to sign in to.
            Toasts.error("That account no longer exists. Create one to carry on.");
            return "/register.xhtml?faces-redirect=true";
        }

        currentUser.login(user);
        Toasts.success("Your email address is confirmed. Welcome to TripMate.");
        return "/dashboard.xhtml?faces-redirect=true";
    }

    /** The request being answered; see {@code ForgotPasswordBean#request}. */
    private static jakarta.servlet.http.HttpServletRequest request() {
        Object request = FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();
        return request instanceof jakarta.servlet.http.HttpServletRequest http ? http : null;
    }

    // ------------------------------------------------------------------
    // What the page shows
    // ------------------------------------------------------------------

    /** @return the address the code went to, for the sentence on the page */
    public String getEmail() {
        return pendingVerification.getEmail();
    }

    /**
     * One line about the code: in the inbox, run out, or never sent.
     *
     * <p>Three states, and the middle one is why this cannot simply ask whether
     * a code is usable. An expired code and one that was never requested are
     * both "nothing you can type in", and they need different sentences: one
     * says the message arrived too long ago, the other says no message is
     * coming. The service answers with an expiry rather than a yes or no so
     * that the difference survives.
     *
     * <p>Read on each render rather than kept in a field, because the answer
     * changes while the page sits open: a code can expire between the render
     * that promised fifteen minutes and the one after somebody comes back from
     * lunch.
     *
     * @return a sentence naming the time left, the expiry, or the absence
     */
    public String getCodeState() {
        if (!pendingVerification.isPresent()) {
            return "";
        }
        LocalDateTime expiresAt = emailVerificationService
                .latestCodeExpiry(pendingVerification.getUserId());
        if (expiresAt == null) {
            return "No code is waiting. Send one and it will arrive in a moment.";
        }

        long seconds = Duration.between(LocalDateTime.now(), expiresAt).toSeconds();
        if (seconds <= 0) {
            return "That code has expired. Send a new one and it will work straight away.";
        }
        // Rounded up, so a code with fifty seconds left reads as a minute rather
        // than as none.
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "The code expires in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
