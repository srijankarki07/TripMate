package com.tripmate.web.backing;

import java.io.Serializable;

import com.tripmate.dto.UserDTO;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.web.filter.AuthenticationFilter;

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
 */
@Named
@RequestScoped
public class LoginBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private UserServiceRemote userService;

    @Inject
    private CurrentUser currentUser;

    private String email;
    private String password;
    private String error;

    /**
     * @return a redirect to the dashboard on success, or null to redisplay the
     *         form with an error
     */
    public String login() {
        error = null;

        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            error = "Enter your email and password.";
            return null;
        }

        UserDTO user = userService.login(email.trim(), password);
        if (user == null) {
            // Deliberately one message for both an unknown email and a wrong
            // password. Saying which was wrong would let anyone test whether an
            // address is registered, which is the same reason the service
            // returns null rather than distinguishing them.
            error = "That email and password do not match an account.";
            return null;
        }

        currentUser.login(user);
        return destinationAfterLogin();
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

    public String getError() {
        return error;
    }
}
