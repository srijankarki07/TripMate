package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;
import com.tripmate.web.support.Initials;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;

/**
 * The signed-in user for the current browser session.
 *
 * <p>Session-scoped, which is the one piece of genuine conversational state in
 * the application. Everything in the business tier is stateless; this holds the
 * identity that the business tier deliberately does not track, because a
 * stateless session bean cannot know who is calling it.
 *
 * <p>Stores the {@link UserDTO}, never the entity and never the password hash.
 * The DTO has no password field at all, so there is nothing here to leak into a
 * view.
 */
@Named
@SessionScoped
public class CurrentUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private UserDTO user;

    /** @return the signed-in user, or null when nobody is signed in */
    public UserDTO getUser() {
        return user;
    }

    /** @return the user's id, or null when nobody is signed in */
    public Long getUserId() {
        return user == null ? null : user.getUserId();
    }

    public boolean isLoggedIn() {
        return user != null;
    }

    /** @return the user's display name, or empty so a view never shows "null" */
    public String getFullName() {
        return user == null ? "" : user.getFullName();
    }

    /**
     * Up to two initials for the avatar in the navigation bar.
     *
     * <p>The rule lives in {@link Initials}, because the trip's member list and
     * the balance position bar draw the same avatar and must not disagree with
     * this one about the same person.
     *
     * @return one or two upper-case letters, never null and never empty
     */
    public String getInitials() {
        return Initials.of(getFullName());
    }

    /**
     * Signs a user in, and gives them a new session id.
     *
     * <p>Changing the id is what stops session fixation. Without it, an
     * attacker who can plant a session id on the victim before they sign in
     * keeps using that id afterwards, because signing in never changed it. The
     * session's attributes are carried across, so anything already stored, such
     * as the path to return to after signing in, survives.
     *
     * <p>Done here rather than in each backing bean so that every path into a
     * signed-in state, sign-in and registration alike, gets it by construction.
     */
    public void login(UserDTO user) {
        this.user = user;
        renewSessionId();
    }

    private static void renewSessionId() {
        Object request = jakarta.faces.context.FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();
        if (request instanceof jakarta.servlet.http.HttpServletRequest http) {
            try {
                http.changeSessionId();
            } catch (IllegalStateException none) {
                // No session to change, which cannot happen on a path that just
                // authenticated someone. Ignored rather than thrown: refusing to
                // sign someone in because the id could not be rotated would be a
                // worse outcome than signing them in on the id they have.
            }
        }
    }

    /**
     * Ends the session.
     *
     * <p>Faces invalidates the session itself rather than only clearing this
     * bean, so nothing else kept in the session survives a sign-out. The
     * navigation outcome is returned so a page can call this directly from a
     * button.
     */
    public String logoutAndReturn() {
        this.user = null;
        jakarta.faces.context.FacesContext.getCurrentInstance()
                .getExternalContext()
                .invalidateSession();
        return "/login.xhtml?faces-redirect=true";
    }

    /** Replaces the cached profile after the user edits it. */
    public void refresh(UserDTO updated) {
        this.user = updated;
    }
}
