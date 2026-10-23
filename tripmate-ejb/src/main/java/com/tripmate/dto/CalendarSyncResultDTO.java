package com.tripmate.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * What one attempt to write a trip into Google Calendars did.
 *
 * <p>Exists because the answer is genuinely plural. A trip can have several
 * members, each with their own connected calendar, and one of them can fail
 * while the others succeed: a reader who revoked access, a calendar that was
 * deleted, a quota that ran out. Reporting a single boolean would throw away the
 * only interesting part, which is who did not get it.
 *
 * <p>Carries no addresses or names, so it can be put in front of a reader or
 * into a log without either learning anything they should not.
 */
public class CalendarSyncResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How many connected calendars the trip was written into. */
    private int calendars;

    /** Calendars that were up to date and needed no call. */
    private int unchanged;

    /** Events created or updated. */
    private int written;

    /** Events removed because their item is no longer planned. */
    private int removed;

    /** Calendars this attempt could not write to at all. */
    private int failed;

    /**
     * Why each failure happened, in Google's own words where it gave any.
     *
     * <p>Kept because the countable half is not actionable on its own. "One
     * calendar could not be updated" tells a reader that something is wrong and
     * nothing about what to do, and the answers are as different as "the
     * Calendar API is not enabled in your Google project" and "somebody
     * revoked access". Bounded, so a trip with many failing calendars cannot
     * turn a sentence into a wall.
     */
    private final List<String> reasons = new ArrayList<>();

    public CalendarSyncResultDTO() {
    }

    public int getCalendars() {
        return calendars;
    }

    public void setCalendars(int calendars) {
        this.calendars = calendars;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public void setUnchanged(int unchanged) {
        this.unchanged = unchanged;
    }

    public int getWritten() {
        return written;
    }

    public void setWritten(int written) {
        this.written = written;
    }

    public int getRemoved() {
        return removed;
    }

    public void setRemoved(int removed) {
        this.removed = removed;
    }

    public int getFailed() {
        return failed;
    }

    /** @return why the failures happened, at most three, never null */
    public List<String> getReasons() {
        return reasons;
    }

    /** Records a failure with its reason, keeping only the first few. */
    public void addFailure(String reason) {
        failed++;
        if (reasons.size() < 3 && reason != null && !reason.isBlank()) {
            reasons.add(reason);
        }
    }

    /** @return true when nothing was written because nobody on the trip has connected */
    public boolean isNobodyConnected() {
        return calendars == 0;
    }

    /**
     * A sentence for the reader, describing what actually happened.
     *
     * <p>Written here rather than in the page so that the same words describe
     * the same event whether it is reported by an automatic sync or by the
     * reader pressing the button.
     *
     * @return one sentence, never null
     */
    public String describe() {
        if (isNobodyConnected()) {
            return "Nobody on this trip has connected a Google Calendar.";
        }

        // Nothing written and something failed is not "up to date". Saying so
        // was this method's first version, and it told a reader their calendar
        // was current at the exact moment every attempt to reach it had been
        // refused.
        if (written == 0 && removed == 0 && failed > 0) {
            return "Google Calendar could not be updated. " + firstReason();
        }

        StringBuilder text = new StringBuilder();
        if (written == 0 && removed == 0) {
            text.append("Your Google Calendar was already up to date.");
        } else {
            text.append("Google Calendar updated: ")
                    .append(written).append(written == 1 ? " event" : " events");
            if (removed > 0) {
                text.append(", ").append(removed).append(" removed");
            }
            text.append('.');
        }
        if (failed > 0) {
            text.append(' ').append(failed)
                    .append(failed == 1 ? " calendar could not be updated: "
                            : " calendars could not be updated: ")
                    .append(firstReason());
        }
        return text.toString();
    }

    private String firstReason() {
        return reasons.isEmpty() ? "no reason was given." : reasons.get(0);
    }
}
