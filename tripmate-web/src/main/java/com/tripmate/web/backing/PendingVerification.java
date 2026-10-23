package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;

/**
 * The account waiting to have its email address confirmed.
 *
 * <h2>Why the account is held here and not in the form</h2>
 *
 * The verification page has to know which account it is confirming, and the
 * account cannot be a request parameter or a hidden field. Either would let
 * anyone ask the application to check a code against somebody else's account,
 * and more to the point would let anyone reach the page, which is the page that
 * sends codes.
 *
 * <p>So it is put in the session, and only ever from a moment when the person
 * has already proved they are the account holder: registering it, or supplying
 * its password at sign-in. The verification page then reads it and cannot be
 * made to read anything else. {@code EmailVerificationService.verify} takes the
 * account from here rather than from the form for exactly that reason.
 *
 * <h2>Why the address and the name are kept too</h2>
 *
 * So the page can say which address the code went to without a lookup, and
 * without the person having to remember which one they typed. That sentence is
 * the only way to notice a typo in the address before it costs the account: an
 * account registered against an address nobody can read is unusable, and the
 * person staring at "we sent a code to jhon@example.com" is the only one who
 * can spot it.
 *
 * <p>Neither field is a secret. Both were typed by the person holding the
 * session, and both are already in {@link CurrentUser} once they are signed in.
 */
@Named
@SessionScoped
public class PendingVerification implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String email;
    private String fullName;

    /**
     * Remembers the account, replacing any earlier one.
     *
     * <p>Replaced rather than merged: somebody who registers a second account
     * in the same browser is confirming the second one, and leaving the first
     * in place would send them back to it.
     */
    public void remember(UserDTO user) {
        this.userId = user == null ? null : user.getUserId();
        this.email = user == null ? null : user.getEmail();
        this.fullName = user == null ? null : user.getFullName();
    }

    /**
     * Forgets the account, once it is confirmed.
     *
     * <p>Called on success, so a session that has just been let in cannot have
     * a stale account confirmed against it by a page reached with the back
     * button.
     */
    public void clear() {
        this.userId = null;
        this.email = null;
        this.fullName = null;
    }

    /** @return whether there is an account waiting to be confirmed */
    public boolean isPresent() {
        return userId != null;
    }

    /** @return the account's id, or null when there is none */
    public Long getUserId() {
        return userId;
    }

    /** @return the address the code went to, or null when there is none */
    public String getEmail() {
        return email;
    }

    /** @return the name to greet, or an empty string so no view shows "null" */
    public String getFullName() {
        return fullName == null ? "" : fullName;
    }
}
