package com.tripmate.entity;

/**
 * Lifecycle state of a {@link Trip}.
 *
 * <p>Persisted with {@code @Enumerated(EnumType.STRING)} so the database stores
 * the readable name. Ordinal storage would silently re-interpret every existing
 * row if a constant were ever inserted in the middle of this list; the
 * {@code ck_trips_status} CHECK constraint names these four values, so adding
 * one here also means updating {@code database/schema.sql}.
 */
public enum TripStatus {

    /** Being organised; dates and members may still change. */
    PLANNING,

    /** Under way right now. */
    ONGOING,

    /** Finished. */
    COMPLETED,

    /** Called off. */
    CANCELLED
}
