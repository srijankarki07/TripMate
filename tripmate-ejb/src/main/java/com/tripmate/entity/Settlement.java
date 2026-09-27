package com.tripmate.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A real-world repayment between two members of a trip.
 *
 * <p>Maps to the {@code settlements} table. This is a record of money that
 * actually changed hands, not a computed figure: the balance queries derive who
 * <em>should</em> pay whom, and a settlement records that they did.
 *
 * <p>Both {@link #payer} and {@link #receiver} point at {@link User}, so this
 * entity has two independent many-to-one associations to the same type. The
 * {@code ck_settlements_distinct_parties} constraint stops them being the same
 * person.
 */
@Entity
@Table(name = "settlements")
public class Settlement implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "settlementSeq")
    @SequenceGenerator(
            name = "settlementSeq",
            sequenceName = "settlements_settlement_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** The member handing money over. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payer_id", nullable = false)
    private User payer;

    /** The member receiving it. Always a different person from {@link #payer}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @PrePersist
    void prePersist() {
        if (settledAt == null) {
            settledAt = LocalDateTime.now();
        }
    }

    // ------------------------------------------------------------------
    // Getters and setters
    // ------------------------------------------------------------------

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getPayer() {
        return payer;
    }

    public void setPayer(User payer) {
        this.payer = payer;
    }

    public User getReceiver() {
        return receiver;
    }

    public void setReceiver(User receiver) {
        this.receiver = receiver;
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

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Settlement settlement)) {
            return false;
        }
        return settlementId != null && settlementId.equals(settlement.settlementId);
    }

    @Override
    public int hashCode() {
        return Settlement.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "Settlement{settlementId=" + settlementId + ", amount=" + amount
                + ", settledAt=" + settledAt + "}";
    }
}
