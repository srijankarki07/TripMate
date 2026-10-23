package com.tripmate.ejb;

import java.util.List;

import com.tripmate.dto.UserDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.entity.User;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.security.PasswordHasher;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;

/**
 * Registration, authentication and lookup for users.
 *
 * <p>Container-managed transactions: every method runs in a transaction the
 * container starts and commits, so there is no begin or commit anywhere in this
 * class. The default {@code REQUIRED} attribute also means that when one of
 * these methods is called from inside another bean's transaction, it joins that
 * transaction rather than starting its own.
 */
@Stateless
public class UserService implements UserServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * Injected through its no-interface view, which is local to this module, so
     * no remote interface is needed or wanted: confirming an address is an
     * internal concern of the business tier and nothing outside it should be
     * able to trigger a code.
     *
     * <p>The dependency runs one way only. This bean asks that one to send a
     * code; that one never asks this one for anything, which is what keeps
     * registering and confirming from being tangled together.
     */
    @EJB
    private EmailVerificationService emailVerificationService;

    @Override
    public UserDTO register(UserDTO user, String plainPassword) {
        require(user, "user");
        require(user.getEmail(), "email");
        require(user.getFullName(), "fullName");
        require(plainPassword, "password");

        String email = user.getEmail().trim();

        // Checked here rather than left to the unique constraint so the caller
        // gets a sentence instead of a constraint violation. The constraint
        // still stands behind this: two concurrent registrations of the same
        // address would both pass this check, and only one would commit.
        if (findByEmail(email) != null) {
            throw new ValidationException("email already registered: " + email);
        }

        User entity = new User();
        entity.setFullName(user.getFullName().trim());
        entity.setEmail(email);
        entity.setPhone(user.getPhone());
        entity.setPasswordHash(PasswordHasher.hash(plainPassword));

        em.persist(entity);
        em.flush(); // so the generated id and created_at are populated

        // The confirming code goes out here rather than from the web tier,
        // because registering is the event and this is where it happens. The
        // address starts unconfirmed, and the row and the code commit together:
        // there is no window in which an account exists that nobody can
        // confirm.
        //
        // The welcome message is not sent here any more. It waits until the
        // address has answered. See EmailVerificationService.
        //
        // The source address is null because this bean has no request and the
        // business tier deliberately has no way to find one. It costs the
        // record a detail and costs the limit nothing: the cap counts codes per
        // account, not per address, so it works the same either way.
        emailVerificationService.issueIfNoneOutstanding(entity.getUserId(), null);

        return UserDTO.from(entity);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>A returned user is not a signed-in user</h2>
     *
     * This answers one question: do these credentials belong together, and to
     * an account? It deliberately does not also answer whether that account may
     * be used, because that second question has a different answer for
     * different callers.
     *
     * <p>An account whose address has not been confirmed still authenticates.
     * It has to: the person typed the right password, and the only way back to
     * the confirmation page after closing the tab is to sign in again and be
     * sent there. So the returned {@link UserDTO} carries
     * {@code emailVerified}, and the caller decides. {@code LoginBean} sends an
     * unconfirmed account to the verification page; {@code ProfileBean} uses
     * this to re-check a password before changing it, where confirmation is
     * irrelevant because the caller is already signed in.
     *
     * <p>Putting that decision here instead would mean this method could not
     * serve both, and the check would have to be duplicated wherever an account
     * is admitted or a password is re-checked.
     */
    @Override
    public UserDTO login(String email, String plainPassword) {
        if (email == null || plainPassword == null) {
            return null;
        }

        User user = findByEmail(email.trim());
        if (user == null) {
            // Deliberately still spends the time a real verification would.
            // Returning immediately would make an unregistered address
            // measurably faster to reject than a registered one.
            PasswordHasher.burnEquivalentWork(plainPassword);
            return null;
        }

        if (!PasswordHasher.verify(plainPassword, user.getPasswordHash())) {
            return null;
        }
        return UserDTO.from(user);
    }

    @Override
    public UserDTO getUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return UserDTO.from(em.find(User.class, userId));
    }

    @Override
    public UserDTO getUserByEmail(String email) {
        if (email == null) {
            return null;
        }
        return UserDTO.from(findByEmail(email.trim()));
    }

    @Override
    public List<UserDTO> getAllUsers() {
        return em.createQuery("SELECT u FROM User u ORDER BY u.fullName", User.class)
                .getResultList()
                .stream()
                .map(UserDTO::from)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Changing the address does not send it back to unconfirmed, and the
     * gap is deliberate. Re-confirming a new address needs the old one to keep
     * working until the new one answers, or somebody who mistypes is left with
     * an account they cannot sign into and cannot correct. That is a
     * change-of-address flow, which this project does not have; the limitation
     * is written down in {@code docs/phase-18-email-verification.md} rather
     * than half-implemented here.
     */
    @Override
    public UserDTO updateUser(UserDTO user) {
        require(user, "user");
        require(user.getUserId(), "userId");

        User entity = em.find(User.class, user.getUserId());
        if (entity == null) {
            throw new ValidationException("no such user: " + user.getUserId());
        }

        // Email is the login identifier, so a change has to respect uniqueness.
        if (user.getEmail() != null && !user.getEmail().equalsIgnoreCase(entity.getEmail())) {
            String email = user.getEmail().trim();
            User clash = findByEmail(email);
            if (clash != null && !clash.getUserId().equals(entity.getUserId())) {
                throw new ValidationException("email already registered: " + email);
            }
            entity.setEmail(email);
        }

        if (user.getFullName() != null) {
            entity.setFullName(user.getFullName().trim());
        }
        if (user.getPhone() != null) {
            entity.setPhone(user.getPhone());
        }

        em.flush();
        return UserDTO.from(entity);
    }

    @Override
    public void changePassword(Long userId, String currentPassword,
                               String newPlainPassword, Long actedBy) {
        require(userId, "userId");
        require(newPlainPassword, "password");

        // Two checks that were missing, and each of them mattered on its own.
        //
        // The actor check is the important one: without it any signed-in user
        // could set any other user's password by id, because the method took a
        // bare user id and never asked who was calling. The web tier only ever
        // passed the signed-in user's own id, so it was not reachable from the
        // interface, but the service was open.
        //
        // The current-password check makes the change deliberate rather than
        // something a hijacked session can do quietly.
        if (actedBy == null || !actedBy.equals(userId)) {
            throw new ValidationException(
                    "a password can only be changed by the person it belongs to");
        }

        User entity = em.find(User.class, userId);
        if (entity == null) {
            throw new ValidationException("no such user: " + userId);
        }
        if (!PasswordHasher.verify(currentPassword, entity.getPasswordHash())) {
            throw new ValidationException("that is not your current password");
        }
        entity.setPasswordHash(PasswordHasher.hash(newPlainPassword));
        em.flush();
    }

    @Override
    public void deleteUser(Long userId) {
        require(userId, "userId");

        User entity = em.find(User.class, userId);
        if (entity == null) {
            throw new ValidationException("no such user: " + userId);
        }
        // Memberships, splits and settlements cascade at the database level;
        // trips the user created keep their rows and lose only created_by.
        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private User findByEmail(String email) {
        try {
            return em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
                    .setParameter("email", email)
                    .getSingleResult();
        } catch (NoResultException none) {
            return null;
        }
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new ValidationException(name + " is required");
        }
    }
}
