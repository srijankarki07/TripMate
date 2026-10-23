package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;
import com.tripmate.ejb.EmailVerificationService;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.web.filter.AuthenticationFilter;
import com.tripmate.web.support.ExternalAddress;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Sign-in.
 *
 * <p>Request-scoped: the credentials are needed for exactly one exchange and
 * must not survive it. Keeping them in a view or session scope would leave a
 * password in memory long after the request that used it.
 *
 * <h2>The third outcome</h2>
 *
 * <p>Right credentials and an address that has never answered is neither a
 * success nor a failure, and it is the reason this bean cannot simply trust the
 * user the service hands back. That account is not admitted, and the sign-in is
 * not refused either: refusing it would be a dead end, because this form is the
 * only way back to the verification page after the browser has been closed. So
 * it becomes a third destination, and a code is on its way before the page
 * loads. See {@link #sendToVerification}.
 */
@Named
@RequestScoped
public class LoginBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private UserServiceRemote userService;

    @EJB
    private EmailVerificationService emailVerificationService;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private PendingVerification pendingVerification;

    private String email;
    private String password;

    /**
     * @return a redirect to the dashboard, or to the verification page for an
     *         account whose address has not answered, or null to redisplay the
     *         form with an error
     */
    public String login() {

        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            Toasts.error("Enter your email and password.");
            return null;
        }

        UserDTO user = userService.login(email.trim(), password);
        if (user == null) {
            // Deliberately one message for both an unknown email and a wrong
            // password. Saying which was wrong would let anyone test whether an
            // address is registered, which is the same reason the service
            // returns null rather than distinguishing them.
            Toasts.error("That email and password do not match an account.");
            return null;
        }

        if (!user.isEmailVerified()) {
            return sendToVerification(user);
        }

        currentUser.login(user);
        return destinationAfterLogin();
    }

    /**
     * Sends somebody whose address has not answered back to the code page.
     *
     * <p>A fresh code only if there is not one already waiting: a person who
     * signs in twice while the first message is in flight should not have the
     * first code quietly replaced under them. That decision belongs to the
     * service, which is why the call is the quiet variant rather than a
     * send-now.
     *
     * <p>This is also what makes an expired code recoverable. The state that
     * expired is the code, not the account: signing in still works, this method
     * still runs, and a new code goes out to replace the dead one. Nobody is
     * left holding an account they cannot finish.
     */
    private String sendToVerification(UserDTO user) {
        emailVerificationService.issueIfNoneOutstanding(user.getUserId(),
                ExternalAddress.clientAddress(request()));
        pendingVerification.remember(user);
        Toasts.info("Confirm your email address to finish signing in. "
                + "We have sent a code to " + user.getEmail() + ".");
        return "/verify-email.xhtml?faces-redirect=true";
    }

    /** The request being answered; see {@code ForgotPasswordBean#request}. */
    private static jakarta.servlet.http.HttpServletRequest request() {
        Object request = jakarta.faces.context.FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();
        return request instanceof jakarta.servlet.http.HttpServletRequest http ? http : null;
    }

    /**
     * Returns the user to the page they were trying to reach, or to the
     * dashboard when they came straight to the sign-in page.
     */
    private String destinationAfterLogin() {
        Object request = jakarta.faces.context.FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();

        if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest) {
            String target = AuthenticationFilter.consumeAfterLoginPath(httpRequest);
            if (target != null && !target.isBlank()) {
                return target + "?faces-redirect=true";
            }
        }
        return "/dashboard.xhtml?faces-redirect=true";
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
