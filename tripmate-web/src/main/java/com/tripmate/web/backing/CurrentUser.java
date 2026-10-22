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

    /**
     * Up to two initials for the avatar in the navigation bar.
     *
     * <p>Derived from the first and last word of the name rather than the first
     * two, so "Srijan Karki" gives "SK" rather than "SR". A single-word name
     * gives one letter. A name that yields nothing usable falls back to a
     * question mark, so the avatar is never an empty circle.
     *
     * <p>Bounded to two characters because that is what the avatar is sized
     * for; a longer string would overflow the circle on a narrow screen.
     *
     * @return one or two upper-case letters, never null and never empty
     */
    public String getInitials() {
        String name = getFullName().trim();
        if (name.isEmpty()) {
            return "?";
        }

        String[] words = name.split("\\s+");
        StringBuilder initials = new StringBuilder();
        initials.append(firstLetter(words[0]));
        if (words.length > 1) {
            initials.append(firstLetter(words[words.length - 1]));
        }

        String result = initials.toString().toUpperCase();
        return result.isEmpty() ? "?" : result;
    }

    private static char firstLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                return word.charAt(i);
            }
        }
        return ' ';
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
