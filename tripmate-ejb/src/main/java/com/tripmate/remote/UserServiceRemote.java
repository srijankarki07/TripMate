package com.tripmate.remote;

import java.util.List;

import com.tripmate.dto.UserDTO;

import jakarta.ejb.Remote;

/**
 * User registration, authentication and lookup.
 *
 * <p>A remote interface, so the web tier reaches it across the EAR boundary and
 * the client never sees a {@code User} entity or a password hash. Everything
 * crossing this boundary is a {@link UserDTO}, which deliberately has no
 * password field at all.
 *
 * <p>Stateless: no operation here needs conversational state between calls, so
 * the container is free to pool and reuse instances.
 */
@Remote
public interface UserServiceRemote {

    /**
     * Registers a new user.
     *
     * @param user          profile fields; {@code userId} is ignored and assigned
     * @param plainPassword the raw password, hashed before it touches the database
     * @return the stored user, with the generated id
     * @throws IllegalArgumentException if the email is already registered or a
     *                                  required field is missing
     */
    UserDTO register(UserDTO user, String plainPassword);

    /**
     * Authenticates an email and password.
     *
     * <p>Returns {@code null} for both an unknown email and a wrong password.
     * Distinguishing them would let anyone probe which addresses are registered,
     * so the failure is deliberately indistinguishable.
     *
     * @return the user on success, {@code null} on any failure
     */
    UserDTO login(String email, String plainPassword);

    /** @return the user, or {@code null} if no such id */
    UserDTO getUser(Long userId);

    /** @return the user, or {@code null} if no account uses that email */
    UserDTO getUserByEmail(String email);

    /** @return every user, ordered by name */
    List<UserDTO> getAllUsers();

    /**
     * Updates profile fields. The password is not touched here; use
     * {@link #changePassword}.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    UserDTO updateUser(UserDTO user);

    /**
     * Replaces a user's password.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    void changePassword(Long userId, String newPlainPassword);

    /**
     * Deletes a user.
     *
     * <p>Their memberships, splits and settlements go with them, and the trips
     * they created survive with a null creator, because the schema uses
     * ON DELETE CASCADE and ON DELETE SET NULL respectively.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    void deleteUser(Long userId);
}
