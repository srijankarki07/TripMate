package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

import com.tripmate.entity.TripStatus;

/**
 * What one trip cost in total, for comparing trips against each other.
 *
 * <p>Assembled by a JPQL constructor expression, so it has no
 * {@code from(entity)} mapper.
 */
public class TripTotalDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private String tripName;
    private BigDecimal total;
    private LocalDate startDate;
    private TripStatus status;
    private Long memberCount;

    public TripTotalDTO() {
    }

    public TripTotalDTO(Long tripId, String tripName, BigDecimal total,
                        LocalDate startDate, TripStatus status, Long memberCount) {
        this.tripId = tripId;
        this.tripName = tripName;
        this.total = total;
        this.startDate = startDate;
        this.status = status;
        this.memberCount = memberCount;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getTripName() {
        return tripName;
    }

    public void setTripName(String tripName) {
        this.tripName = tripName;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
    }

    public Long getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Long memberCount) {
        this.memberCount = memberCount;
    }

    /**
     * @return the total divided by the number of members, or zero when nobody
     *         is on the trip
     *
     * <p>The figure that actually makes two trips comparable. A week away for
     * six people costing twice what a weekend for three cost is cheaper per
     * head, and the raw totals say the opposite.
     */
    public BigDecimal getPerPerson() {
        if (total == null || memberCount == null || memberCount == 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(BigDecimal.valueOf(memberCount), 2, java.math.RoundingMode.HALF_UP);
    }
}
