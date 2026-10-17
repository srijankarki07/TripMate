package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

import com.tripmate.entity.TripMember;
import com.tripmate.entity.TripRole;

/**
 * One member of a trip, with the role they hold on that trip.
 *
 * <p>Flattens {@code TripMember.user.fullName} into {@link #fullName} so the
 * members list on the trip page renders from a single query. The user's email
 * and phone are deliberately absent: a member list needs to identify people,
 * not to hand out their contact details to everyone else on the trip.
 *
 * <p>{@link TripRole} is reused from the entity package. It is a domain value
 * with no JPA annotations, so copying it here would only create two enums to
 * keep in step.
 */
public class TripMemberDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long memberId;
    private Long userId;
    private String fullName;
    private TripRole role;
    private LocalDateTime joinedAt;

    public TripMemberDTO() {
    }

    public TripMemberDTO(Long memberId, Long userId, String fullName,
                         TripRole role, LocalDateTime joinedAt) {
        this.memberId = memberId;
        this.userId = userId;
        this.fullName = fullName;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Traverses {@code member.user}, a lazy association, so it must run
     * while the persistence context is open. The user is resolved once into a
     * local rather than read twice.
     */
    public static TripMemberDTO from(TripMember member) {
        if (member == null) {
            return null;
        }
        var user = member.getUser();
        return new TripMemberDTO(
                member.getMemberId(),
                user == null ? null : user.getUserId(),
                user == null ? null : user.getFullName(),
                member.getRole(),
                member.getJoinedAt());
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
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

    public TripRole getRole() {
        return role;
    }

    public void setRole(TripRole role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TripMemberDTO dto)) {
            return false;
        }
        return memberId != null && memberId.equals(dto.memberId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(memberId);
    }

    @Override
    public String toString() {
        return "TripMemberDTO{memberId=" + memberId + ", fullName='" + fullName
                + "', role=" + role + "}";
    }
}
