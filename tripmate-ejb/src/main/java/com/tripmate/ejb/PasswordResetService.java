package com.tripmate.ejb;

import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;

import com.tripmate.entity.PasswordReset;
import com.tripmate.entity.User;
import com.tripmate.exception.ValidationException;
import com.tripmate.mail.MailService;
import com.tripmate.mail.Templates;
import com.tripmate.security.PasswordHasher;
import com.tripmate.security.TokenHasher;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Forgot-password, from the request to the new password.
 *
 * <h2>The rules this is built around</h2>
 *
 * <ol>
 *   <li><b>The reply never says whether the address has an account.</b> A
 *       differing reply turns the form into a way to test whether somebody uses
 *       this site, which is the same reason sign-in refuses an unknown address
 *       and a wrong password with one message.</li>
 *   <li><b>The token is stored hashed.</b> See {@link TokenHasher}. A stolen
 *       copy of the table is not a set of keys.</li>
 *   <li><b>One use, and a short life.</b> Thirty minutes and a single
 *       redemption. Requesting a new link spends every earlier one, so an old
 *       message lying in an inbox stops being a way in.</li>
 *   <li><b>Changing the password spends every outstanding link.</b> Otherwise a
 *       second link, sent to an address the attacker still controls, would keep
 *       working after the owner had noticed and reacted.</li>
 *   <li><b>The token is the whole authorisation for the change.</b> There is no
 *       other way to set a password without knowing the current one, which is
 *       why the write lives here rather than in {@code UserService}.</li>
 * </ol>
 */
@Stateless
public class PasswordResetService {

    private static final Logger LOG = Logger.getLogger(PasswordResetService.class.getName());

    /** How long a link stays good for. Short enough to limit a leaked inbox. */
    private static final int VALID_MINUTES = 30;

    /**
     * How many links one account may ask for in the window below.
     *
     * <p>Enough for somebody who mistyped their address once and did not get the
     * message, few enough that the form cannot be used to post mail at somebody
     * repeatedly.
     */
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private static final int WINDOW_MINUTES = 15;

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    @EJB
    private MailService mailService;

    /**
     * Starts a reset, if the address has an account.
     *
     * <p>Returns nothing and reports nothing either way. The caller shows the
     * same sentence whether or not anything happened, which is the only way the
     * reply can avoid answering the question it must not answer.
     *
     * @param email       the address as typed
     * @param requestedIp where the request came from, kept for the rate limit
     */
    public void requestReset(String email, String requestedIp) {
        if (email == null || email.isBlank()) {
            return;
        }

        User user = findByEmail(email.trim());
        if (user == null) {
            // Deliberately silent, and deliberately quick. If this branch did
            // the same work as the other one there would be nothing to time, but
            // there is also nothing here worth the cost of pretending.
            LOG.info("Password reset requested for an address with no account");
            return;
        }

        if (tooManyRequests(user)) {
            // Also silent. Telling the requester they have been rate limited
            // confirms the address has an account, which is the one thing the
            // reply must not do. Logged so an operator can see it happening.
            LOG.warning("Password reset rate limited for user " + user.getUserId());
            return;
        }

        spendOutstanding(user);

        String token = TokenHasher.newToken();
        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(TokenHasher.hash(token));
        reset.setExpiresAt(LocalDateTime.now().plusMinutes(VALID_MINUTES));
        reset.setRequestedIp(requestedIp);
        em.persist(reset);
        em.flush();

        mailService.send(Templates.passwordReset(user.getEmail(), user.getFullName(),
                mailService.getBaseUrl() + "/reset-password.xhtml?token=" + token,
                VALID_MINUTES));
    }

    /**
     * Checks a token without spending it, so the page can greet the holder.
     *
     * @return the name to greet, or null when the token is unknown, expired or
     *         already spent
     */
    public String describeToken(String token) {
        User user = userFor(token);
        return user == null ? null : user.getFullName();
    }

    /**
     * Sets a new password, and spends the token that authorised it.
     *
     * <p>The only path in the application that changes a password without the
     * current one, which is what a reset is for. Everything else routes through
     * {@code UserService.changePassword}, which insists on both the owner and
     * the password in force.
     *
     * @throws ValidationException when the token is not usable, or the password
     *                             is too short
     */
    public void consumeReset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("Choose a password of at least 8 characters.");
        }

        PasswordReset reset = resetFor(token);
        if (reset == null) {
            throw new ValidationException(
                    "That reset link is not valid any more. Ask for a new one.");
        }

        User user = reset.getUser();
        user.setPasswordHash(PasswordHasher.hash(newPassword));
        reset.setConsumedAt(LocalDateTime.now());

        // Every other outstanding link for this account, not only this one.
        spendOutstanding(user);
        em.flush();

        LOG.info("Password reset completed for user " + user.getUserId()
                + "; outstanding links spent");
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Finds the user a token belongs to, if the token is usable.
     *
     * @return the user, or null for an unknown, expired or spent token
     */
    private User userFor(String token) {
        PasswordReset reset = resetFor(token);
        return reset == null ? null : reset.getUser();
    }

    /**
     * Finds an outstanding reset by the hash of the presented token.
     *
     * <p>Looked up by hash rather than by scanning and comparing, so the
     * comparison the database does is the whole of it and there is no loop whose
     * length depends on the input. The constant-time check in
     * {@link TokenHasher#matches} then guards the case where the lookup itself
     * is not the only comparison.
     *
     * @return the row, or null when it is unknown, expired or already spent
     */
    private PasswordReset resetFor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = TokenHasher.hash(token.trim());
        if (hash == null) {
            return null;
        }

        List<PasswordReset> found = em.createQuery(
                        "SELECT r FROM PasswordReset r JOIN FETCH r.user "
                                + "WHERE r.tokenHash = :hash", PasswordReset.class)
                .setParameter("hash", hash)
                .getResultList();

        if (found.isEmpty()) {
            return null;
        }
        PasswordReset reset = found.get(0);
        // Belt and braces: the lookup already matched on the hash, and this
        // checks it again without an early exit.
        if (!TokenHasher.matches(token.trim(), reset.getTokenHash())) {
            return null;
        }
        return reset.isUsable(LocalDateTime.now()) ? reset : null;
    }

    /**
     * Marks every outstanding link for a user as spent.
     *
     * <p>Marked rather than deleted, so a replay is recognisable as a used link
     * instead of an unknown one. Both are refused, but the log can tell them
     * apart.
     */
    private void spendOutstanding(User user) {
        em.createQuery("UPDATE PasswordReset r SET r.consumedAt = :now "
                        + "WHERE r.user = :user AND r.consumedAt IS NULL")
                .setParameter("now", LocalDateTime.now())
                .setParameter("user", user)
                .executeUpdate();
    }

    /** @return whether this account has asked too often, too recently */
    private boolean tooManyRequests(User user) {
        Long recent = em.createQuery(
                        "SELECT COUNT(r) FROM PasswordReset r "
                                + "WHERE r.user = :user AND r.requestedAt > :since", Long.class)
                .setParameter("user", user)
                .setParameter("since", LocalDateTime.now().minusMinutes(WINDOW_MINUTES))
                .getSingleResult();
        return recent != null && recent >= MAX_REQUESTS_PER_WINDOW;
    }

    /** Looks a user up by exact address, case-insensitively. */
    private User findByEmail(String email) {
        try {
            return em.createQuery(
                            "SELECT u FROM User u WHERE LOWER(u.email) = :email", User.class)
                    .setParameter("email", email.toLowerCase(java.util.Locale.ROOT))
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException none) {
            return null;
        }
    }
}
