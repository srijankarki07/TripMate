package com.tripmate.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import com.tripmate.entity.Settlement;

/**
 * A recorded repayment between two members of a trip.
 *
 * <p>Not listed among the eight DTOs in ROADMAP section 7, but required by the
 * {@code ExpenseServiceRemote} interface in ROADMAP section 8.2, which declares
 * {@code recordSettlement(SettlementDTO)} and
 * {@code getSettlementsForTrip(...)}. The two sections disagree; this class
 * resolves it in favour of the interface that has to compile. Nine DTOs against
 * a target of eight is not a problem, since the assignment asks for DTOs to
 * cross the network rather than for a specific count.
 *
 * <p>Names are flattened into {@link #payerName} and {@link #receiverName} for
 * the same reason as elsewhere: the settle-up view shows who paid whom, and
 * resolving two users per row would be two extra queries per settlement.
 */
public class SettlementDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long settlementId;
    private Long tripId;
    private Long payerId;
    private String payerName;
    private Long receiverId;
    private String receiverName;
    private BigDecimal amount;
    private String note;
    private LocalDateTime settledAt;

    public SettlementDTO() {
    }

    public SettlementDTO(Long settlementId, Long tripId, Long payerId, String payerName,
                         Long receiverId, String receiverName, BigDecimal amount,
                         String note, LocalDateTime settledAt) {
        this.settlementId = settlementId;
        this.tripId = tripId;
        this.payerId = payerId;
        this.payerName = payerName;
        this.receiverId = receiverId;
        this.receiverName = receiverName;
        this.amount = amount;
        this.note = note;
        this.settledAt = settledAt;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Traverses three lazy associations (trip, payer, receiver). Each user
     * is resolved once into a local so the name and the id cannot come from two
     * different reads.
     */
    public static SettlementDTO from(Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        var trip = settlement.getTrip();
        var payer = settlement.getPayer();
        var receiver = settlement.getReceiver();

        return new SettlementDTO(
                settlement.getSettlementId(),
                trip == null ? null : trip.getTripId(),
                payer == null ? null : payer.getUserId(),
                payer == null ? null : payer.getFullName(),
                receiver == null ? null : receiver.getUserId(),
                receiver == null ? null : receiver.getFullName(),
                settlement.getAmount(),
                settlement.getNote(),
                settlement.getSettledAt());
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getPayerId() {
        return payerId;
    }

    public void setPayerId(Long payerId) {
        this.payerId = payerId;
    }

    public String getPayerName() {
        return payerName;
    }

    public void setPayerName(String payerName) {
        this.payerName = payerName;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SettlementDTO dto)) {
            return false;
        }
        return settlementId != null && settlementId.equals(dto.settlementId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(settlementId);
    }

    @Override
    public String toString() {
        return "SettlementDTO{settlementId=" + settlementId + ", payerName='" + payerName
                + "', receiverName='" + receiverName + "', amount=" + amount + "}";
    }
}
