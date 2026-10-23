package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.tripmate.entity.SummaryDeliveryStatus;
import com.tripmate.entity.TripSummaryEmail;
import com.tripmate.entity.TripSummaryKind;

/**
 * One line of the trip summary log: who asked for the summary, when, what it
 * was about, and how many people it reached.
 *
 * <p>Carries the sender's name as a string rather than a user id, because the
 * name is what the log is for and the entity stores it denormalised so the line
 * keeps reading correctly after that person leaves the trip.
 *
 * <p>Carries the outcome as well as the count. The count alone reads as "this
 * arrived", and a send that failed while the mail server was down produced the
 * same line as one that did not.
 *
 * <p>There is no {@code tripId}. Every row in the list belongs to the trip
 * whose page is being read, so a field repeating that would be noise. The
 * exception is {@link com.tripmate.dto.SettlementDTO}, which does carry one
 * because settlements are also read across every trip at once; this is not.
 */
public class TripSummaryEmailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long summaryId;
    private String sentByName;
    private TripSummaryKind kind;
    private Integer recipientCount;
    private String recipientNames;
    private Integer failedCount;
    private SummaryDeliveryStatus status;
    private LocalDateTime sentAt;

    public TripSummaryEmailDTO() {
    }

    public TripSummaryEmailDTO(Long summaryId, String sentByName, TripSummaryKind kind,
            Integer recipientCount, String recipientNames, Integer failedCount,
            SummaryDeliveryStatus status, LocalDateTime sentAt) {
        this.summaryId = summaryId;
        this.sentByName = sentByName;
        this.kind = kind;
        this.recipientCount = recipientCount;
        this.recipientNames = recipientNames;
        this.failedCount = failedCount;
        this.status = status;
        this.sentAt = sentAt;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Reads {@code sentByName} off the entity rather than through the
     * {@code sentBy} association, which is lazy and may point at nothing: the
     * whole reason the name is stored twice is so that this mapper never has to
     * touch the join.
     *
     * @param entity the row, which must not be null
     * @return the DTO
     */
    public static TripSummaryEmailDTO from(TripSummaryEmail entity) {
        return new TripSummaryEmailDTO(
                entity.getSummaryId(),
                entity.getSentByName(),
                entity.getKind(),
                entity.getRecipientCount(),
                entity.getRecipientNames(),
                entity.getFailedCount(),
                entity.getStatus(),
                entity.getSentAt());
    }

    public Long getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(Long summaryId) {
        this.summaryId = summaryId;
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
        this.recipientNames = recipientNames;
    }

    /**
     * The recipients as a list, for the detail panel.
     *
     * <p>Split here rather than in the view so the separator is a property of
     * how the value is stored, which is this class's business.
     *
     * @return the names, in the order they were written to; empty when the
     *         entry predates the column
     */
    public java.util.List<String> getRecipientNameList() {
        if (recipientNames == null || recipientNames.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(recipientNames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
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
