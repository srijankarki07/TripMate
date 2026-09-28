package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.ejb.PasswordResetService;
import com.tripmate.web.support.Messages;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * The form somebody reaches when they cannot sign in.
 *
 * <h2>One reply, whatever happened</h2>
 *
 * {@link #submit} sets {@code submitted} and says nothing else. It does not
 * record whether the address had an account, because the page must not be able
 * to tell the difference: a form that answers "no such user" is a way to test
 * whether any given address uses this site, and that is exactly what an
 * attacker with a list of addresses wants.
 *
 * <p>So the page shows one sentence whether the address exists, does not exist,
 * or has asked too often. The only person who learns anything is somebody who
 * can read the inbox, which is the point of the whole flow.
 */
@Named
@RequestScoped
public class ForgotPasswordBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private PasswordResetService passwordResetService;

    private String email;
    private boolean submitted;

    /**
     * Asks for a link, and reports nothing about the outcome.
     *
     * @return null, so the page redisplays with the confirmation
     */
    public String submit() {
        if (email != null && !email.isBlank()) {
            try {
                passwordResetService.requestReset(email.trim(), clientAddress());
            } catch (RuntimeException ex) {
                // A failure here is the application's, not the requester's, and
                // reporting it would say something about the address that the
                // reply must not say. Logged by Messages.describe and swallowed.
                Messages.describe(ex);
            }
        }
        submitted = true;
        return null;
    }

    /**
     * @return the caller's address, for the rate limit
     *
     * <p>The socket address, not a forwarding header. A header can be set by
     * anyone, so trusting one without a proxy in front would let a caller
     * present a new address on every request and never be limited. Behind a
     * real proxy this is the line that would change.
     */
    private static String clientAddress() {
        Object request = jakarta.faces.context.FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();
        if (request instanceof jakarta.servlet.http.HttpServletRequest http) {
            String address = http.getRemoteAddr();
            return address == null ? null : address.substring(0, Math.min(45, address.length()));
        }
        return null;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isSubmitted() {
        return submitted;
    }
}
