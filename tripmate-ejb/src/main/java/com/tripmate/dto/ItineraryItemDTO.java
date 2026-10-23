package com.tripmate.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

import com.tripmate.entity.ItineraryItem;

/**
 * One scheduled activity within a trip.
 *
 * <p>Carries {@code authorName} rather than the author's identity, because the
 * itinerary view shows who added an item and nothing more. There is no
 * {@code tripId}: an itinerary item is only ever read in the context of the
 * trip it belongs to, and the write operations take the trip id as a separate
 * parameter, so repeating it in the payload would be redundant state that could
 * disagree with the argument.
 *
 * <p>{@code revision} and {@code updatedAt} carry no meaning to the pages and
 * are read by the calendar feed alone, which writes them as the event's
 * {@code SEQUENCE} and {@code LAST-MODIFIED}. They travel on this DTO because
 * the feed is built from the itinerary the query returns, and the order that
 * query defines is the order a calendar has to show the day in. Querying the
 * entities a second time for the feed would be a second definition of that
 * order, which is exactly what this DTO exists to avoid.
 */
public class ItineraryItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long itemId;
    private String title;
    private String description;
    private LocalDate itemDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;
    private String authorName;
    private int revision;
    private LocalDateTime updatedAt;

    public ItineraryItemDTO() {
    }

    public ItineraryItemDTO(Long itemId, String title, String description,
                            LocalDate itemDate, LocalTime startTime, LocalTime endTime,
                            String location, String authorName,
                            int revision, LocalDateTime updatedAt) {
        this.itemId = itemId;
        this.title = title;
        this.description = description;
        this.itemDate = itemDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.location = location;
        this.authorName = authorName;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    /**
     * Maps an entity to its DTO.
     *
     * <p>Traverses {@code item.createdBy}, a lazy association. It is nullable
     * in the schema (the column uses ON DELETE SET NULL), so a missing author
     * is an expected state and yields a null name rather than an error.
     */
    public static ItineraryItemDTO from(ItineraryItem item) {
        if (item == null) {
            return null;
        }
        var author = item.getCreatedBy();
        return new ItineraryItemDTO(
                item.getItemId(),
                item.getTitle(),
                item.getDescription(),
                item.getItemDate(),
                item.getStartTime(),
                item.getEndTime(),
                item.getLocation(),
                author == null ? null : author.getFullName(),
                item.getRevision(),
                item.getUpdatedAt());
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getItemDate() {
        return itemDate;
    }

    public void setItemDate(LocalDate itemDate) {
        this.itemDate = itemDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    /** @return how many times this item has been edited; never negative */
    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /** @return when this item was last written, or null on a DTO the view is filling in */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItineraryItemDTO dto)) {
            return false;
        }
        return itemId != null && itemId.equals(dto.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(itemId);
    }

    @Override
    public String toString() {
        return "ItineraryItemDTO{itemId=" + itemId + ", title='" + title
                + "', itemDate=" + itemDate + ", startTime=" + startTime + "}";
    }
}
