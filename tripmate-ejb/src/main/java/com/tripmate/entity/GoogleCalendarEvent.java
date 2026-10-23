package com.tripmate.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Which Google event one itinerary item became, for one connected reader.
 *
 * <p>Maps to {@code google_calendar_events}. This is the only place the
 * application keeps a Google event's id, and without it an event it wrote
 * earlier can never be changed or removed: Google identifies an event by that
 * id alone, and nothing else in this schema has ever seen it.
 *
 * <h2>{@code itemId} is nullable on purpose</h2>
 *
 * <p>When an itinerary item is deleted this row survives it, because the event
 * it points at is still in somebody's calendar. The next sync finds a row with
 * no item, removes the event, and deletes the row. Cascading instead would tidy
 * the table and leave the reader with an appointment for a trek that is no
 * longer planned, which is the failure the reader would actually notice.
 *
 * <p>Which is also why the identity is a surrogate id rather than the pair: a
 * column in a primary key cannot be null.
 */
@Entity
@Table(name = "google_calendar_events")
public class GoogleCalendarEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "googleCalendarEventSeq")
    @SequenceGenerator(
            name = "googleCalendarEventSeq",
            sequenceName = "google_calendar_events_link_id_seq",
            allocationSize = 1) // see User.userId for why this must be 1
    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Null once the itinerary item has been deleted and before the event has been. */
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "calendar_id", nullable = false, length = 255)
    private String calendarId;

    @Column(name = "google_event_id", nullable = false, length = 1024)
    private String googleEventId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (calendarId == null || calendarId.isBlank()) {
            calendarId = "primary";
        }
    }

    public Long getLinkId() {
        return linkId;
    }

    public void setLinkId(Long linkId) {
        this.linkId = linkId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(String calendarId) {
        this.calendarId = calendarId;
    }

    public String getGoogleEventId() {
        return googleEventId;
    }

    public void setGoogleEventId(String googleEventId) {
        this.googleEventId = googleEventId;
    }

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
        if (!(other instanceof GoogleCalendarEvent link)) {
            return false;
        }
        return linkId != null && linkId.equals(link.linkId);
    }

    @Override
    public int hashCode() {
        return GoogleCalendarEvent.class.hashCode(); // constant; see User.hashCode
    }

    @Override
    public String toString() {
        return "GoogleCalendarEvent{userId=" + userId + ", itemId=" + itemId + "}";
    }
}
