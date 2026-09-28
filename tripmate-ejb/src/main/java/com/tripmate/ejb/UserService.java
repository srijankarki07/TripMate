package com.tripmate.ejb;

import java.util.List;

import com.tripmate.dto.UserDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.entity.User;
import com.tripmate.mail.MailService;
import com.tripmate.mail.Templates;
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
     * no remote interface is needed or wanted: mail is an internal concern of
     * the business tier and nothing outside it should be able to send.
     */
    @EJB
    private MailService mailService;

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

        // Raised here rather than in the web tier, because registering is the
        // event and this is where it happens. The call is asynchronous and does
        // not throw, so a mail server that is down cannot turn a successful
        // registration into an error page.
        mailService.send(Templates.welcome(
                entity.getEmail(), entity.getFullName(), signInUrl()));

        return UserDTO.from(entity);
    }

    /**
     * The absolute URL of the sign-in page.
     *
     * <p>Built from the base URL the container holds, which
     * {@code scripts/glassfish-mail.sh} sets from {@code TM_APP_BASE_URL}. It
     * cannot be derived from the request: mail is composed on another thread,
     * after the request that caused it has been answered, so there is no request
     * to read a host from.
     *
     * @return the absolute URL, with no double slash if the base ends in one
     */
    private String signInUrl() {
        String base = mailService.getBaseUrl();
        return base.endsWith("/") ? base + "login.xhtml" : base + "/login.xhtml";
    }

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
