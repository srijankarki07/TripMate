package com.tripmate.entity;

/**
 * Which half of a trip the summary email is about.
 *
 * <p>The two used to go out together, and they answer different questions. A
 * trip being planned has an itinerary and no expenses; a trip that has been
 * settled has expenses and nothing left to plan. Sending both halves when only
 * one of them has anything in it is how a summary arrives with an empty
 * section, or with figures that are all zero.
 *
 * <p>Stored on the log entry as its name, so the record of what went out still
 * reads correctly if this enum is ever reordered.
 */
public enum TripSummaryKind {

    /** What is planned: the day-by-day itinerary. */
    ITINERARY("Itinerary"),

    /** What it cost: totals, the breakdown, and where everybody landed. */
    BALANCES("Balance sheet");

    private final String label;

    TripSummaryKind(String label) {
        this.label = label;
    }

    /** @return the name of this half, as the reader sees it on the form */
    public String getLabel() {
        return label;
    }
}
