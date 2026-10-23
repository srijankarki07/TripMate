package com.tripmate.dto;

import java.io.Serializable;

/**
 * A trip's plan rendered as a calendar file, ready to be sent to a browser.
 *
 * <p>Carries the trip's name alongside the file itself so the web tier can name
 * the download after the trip without a second, unauthorised read of it. The
 * service that builds this has already decided the caller may see the trip; a
 * separate lookup for the name would be a read that nothing had authorised.
 *
 * <p>The content is the finished file rather than the data behind it. Rendering
 * belongs with the rules that decide what may be rendered, so a caller cannot
 * take the items and emit something the rules would not have allowed.
 */
public class CalendarFeedDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tripName;
    private String content;

    public CalendarFeedDTO() {
    }

    public CalendarFeedDTO(String tripName, String content) {
        this.tripName = tripName;
        this.content = content;
    }

    /** @return the trip's name, used to name the downloaded file */
    public String getTripName() {
        return tripName;
    }

    public void setTripName(String tripName) {
        this.tripName = tripName;
    }

    /** @return the iCalendar file's text, CRLF-terminated on every line */
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
