package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;

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

    public void login(UserDTO user) {
        this.user = user;
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
