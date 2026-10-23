package com.tripmate.entity;

/**
 * Lifecycle state of a {@link Trip}.
 *
 * <p>Persisted with {@code @Enumerated(EnumType.STRING)} so the database stores
 * the readable name. Ordinal storage would silently re-interpret every existing
 * row if a constant were ever inserted in the middle of this list; the
 * {@code ck_trips_status} CHECK constraint names these four values, so adding
 * one here also means updating {@code database/schema.sql}.
 *
 * <h2>Two of the four are live</h2>
 *
 * <p>The distinction is not the same as "not finished". A trip being planned and
 * a trip under way have one thing in common that a completed or cancelled one
 * does not: somebody is still going to act on the plan. That is what
 * {@link #isLive()} names, and it is what anything asking "should this still be
 * written somewhere else" should ask. Two pages already express it by hand as
 * {@code PLANNING || ONGOING}; this is the same rule with a name.
 */
public enum TripStatus {

    /** Being organised; dates and members may still change. */
    PLANNING(true),

    /** Under way right now. */
    ONGOING(true),

    /** Finished. */
    COMPLETED(false),

    /** Called off. */
    CANCELLED(false);

    private final boolean live;

    TripStatus(boolean live) {
        this.live = live;
    }

    /**
     * Whether this trip is still to come or under way.
     *
     * <p>Asked by the calendar feature, where the answer decides whether a plan
     * is offered to a calendar at all: a completed trip's itinerary is a record
     * of what happened rather than a plan anybody will follow, and a cancelled
     * one's is a plan nobody is following.
     *
     * @return true for {@code PLANNING} and {@code ONGOING}
     */
    public boolean isLive() {
        return live;
    }
}
