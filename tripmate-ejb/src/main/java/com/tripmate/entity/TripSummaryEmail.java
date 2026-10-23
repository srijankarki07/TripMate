package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A record that the closing summary for a {@link Trip} was mailed out.
 *
 * <p>Maps to the {@code trip_summary_emails} table. The summary leaves the
 * application as an email and leaves no other trace of itself, so a member who
 * did not receive one, or who received two, had no way to find out whether it
 * had been sent at all. This is that trace.
 *
 * <h2>Two fields are deliberately denormalised</h2>
 *
 * {@code sentByName} is stored rather than joined. The log has to keep reading
 * correctly after the person who sent it leaves the trip, because the question
 * it answers is "did this go out, and when", and an entry that suddenly reads
 * "sent by unknown" answers it worse than one that reads "sent by Alice".
 *
 * <p>{@code recipientCount} is stored rather than counted at read time for the
 * same reason: it records how many people were actually written to at that
 * moment, not how many belong to the trip now. Those differ as soon as somebody
 * joins or leaves, and it is the first that the log is about.
 *
 * <p>{@code sentBy} is nullable and its foreign key is {@code ON DELETE SET
 * NULL}, so the entry outlives the account. The send still happened and the
 * other members may still be looking for it.
 */
@Entity
@Table(name = "trip_summary_emails")
public class TripSummaryEmail implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tripSummaryEmailSeq")
    @SequenceGenerator(
            name = "tripSummaryEmailSeq",
            sequenceName = "trip_summary_emails_summary_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "summary_id", nullable = false)
    private Long summaryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /**
     * Who asked for it. Optional, because the account can be deleted while the
     * record of what it did stays.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by")
    private User sentBy;

    /** Their display name at the time, so the log still reads after they go. */
    @Column(name = "sent_by_name", nullable = false, length = 120)
    private String sentByName;

    /** Which half of the trip this send was about. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TripSummaryKind kind;

    /**
     * How many people the message was accepted for, which is not the same as
     * the membership now.
     */
    @Column(name = "recipient_count", nullable = false)
    private Integer recipientCount;

    /**
     * Who the message was accepted for, by name, comma-separated.
     *
     * <p>Denormalised for the same reason {@code sentByName} is: the log has to
     * keep reading correctly after somebody leaves the trip. It answers "who
     * did this reach", which the count alone cannot.
     */
    @Column(name = "recipient_names", nullable = false, length = 1000)
    private String recipientNames = "";

    /**
     * How many of the attempted messages were refused.
     *
     * <p>Stored beside the count that succeeded rather than derived from it:
     * "sent to four" and "sent to four, refused for two" are different things
     * to read, and only the second explains why two members never saw it.
     */
    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    /** What became of the send as a whole. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SummaryDeliveryStatus status = SummaryDeliveryStatus.SENT;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    /**
     * Stamps the send time if the caller did not.
     *
     * <p>The same treatment {@code TripMember.joinedAt} gets, and for the same
     * reason: a timestamp that the application always sets and the database
     * also defaults is one that cannot be left null by a caller that forgot.
     */
    @PrePersist
    void stampSentAt() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    public Long getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(Long summaryId) {
        this.summaryId = summaryId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getSentBy() {
        return sentBy;
    }

    public void setSentBy(User sentBy) {
        this.sentBy = sentBy;
    }

    public String getSentByName() {
        return sentByName;
    }

    public void setSentByName(String sentByName) {
        this.sentByName = sentByName;
    }

    public TripSummaryKind getKind() {
        return kind;
    }

    public void setKind(TripSummaryKind kind) {
        this.kind = kind;
    }

    public Integer getRecipientCount() {
        return recipientCount;
    }

    public void setRecipientCount(Integer recipientCount) {
        this.recipientCount = recipientCount;
    }

    public String getRecipientNames() {
        return recipientNames;
    }

    public void setRecipientNames(String recipientNames) {
        this.recipientNames = recipientNames == null ? "" : recipientNames;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public SummaryDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(SummaryDeliveryStatus status) {
        this.status = status;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
