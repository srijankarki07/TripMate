package com.tripmate.ejb;

import java.util.List;

import com.tripmate.dto.UserDTO;
import com.tripmate.entity.User;
import com.tripmate.remote.UserServiceRemote;
import com.tripmate.security.PasswordHasher;

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
            throw new IllegalArgumentException("email already registered: " + email);
        }

        User entity = new User();
        entity.setFullName(user.getFullName().trim());
        entity.setEmail(email);
        entity.setPhone(user.getPhone());
        entity.setPasswordHash(PasswordHasher.hash(plainPassword));

        em.persist(entity);
        em.flush(); // so the generated id and created_at are populated
        return UserDTO.from(entity);
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
            throw new IllegalArgumentException("no such user: " + user.getUserId());
        }

        // Email is the login identifier, so a change has to respect uniqueness.
        if (user.getEmail() != null && !user.getEmail().equalsIgnoreCase(entity.getEmail())) {
            String email = user.getEmail().trim();
            User clash = findByEmail(email);
            if (clash != null && !clash.getUserId().equals(entity.getUserId())) {
                throw new IllegalArgumentException("email already registered: " + email);
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
    public void changePassword(Long userId, String newPlainPassword) {
        require(userId, "userId");
        require(newPlainPassword, "password");

        User entity = em.find(User.class, userId);
        if (entity == null) {
            throw new IllegalArgumentException("no such user: " + userId);
        }
        entity.setPasswordHash(PasswordHasher.hash(newPlainPassword));
        em.flush();
    }

    @Override
    public void deleteUser(Long userId) {
        require(userId, "userId");

        User entity = em.find(User.class, userId);
        if (entity == null) {
            throw new IllegalArgumentException("no such user: " + userId);
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
            throw new IllegalArgumentException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
