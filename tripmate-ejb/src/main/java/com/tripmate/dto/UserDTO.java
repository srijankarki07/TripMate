package com.tripmate.dto;

import java.io.Serializable;
import java.util.Objects;

import com.tripmate.entity.User;

/**
 * A user's profile, as sent across the remote boundary.
 *
 * <p>Note what is missing: {@code passwordHash}. The entity carries it, this
 * does not, and that is the point of having a DTO at all. A field that is never
 * put into the object cannot leak out of the business tier by accident, which
 * is a stronger guarantee than remembering to null it before returning.
 */
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String fullName;
    private String email;
    private String phone;

    /** Required by Java serialization and by CDI proxies. */
    public UserDTO() {
    }

    public UserDTO(Long userId, String fullName, String email, String phone) {
        this.userId = userId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>No associations are traversed, so this never triggers a lazy load and
     * is safe to call from anywhere inside the business tier.
     */
    public static UserDTO from(User user) {
        if (user == null) {
            return null;
        }
        return new UserDTO(
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone());
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * Value equality on the identifier. Two DTOs for the same user are the same
     * user even if one of them was populated from a different query.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserDTO dto)) {
            return false;
        }
        return userId != null && userId.equals(dto.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }

    @Override
    public String toString() {
        return "UserDTO{userId=" + userId + ", fullName='" + fullName
                + "', email='" + email + "'}";
    }
}
