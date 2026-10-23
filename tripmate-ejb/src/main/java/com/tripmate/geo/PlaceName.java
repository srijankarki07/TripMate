package com.tripmate.geo;

import java.io.Serializable;

/**
 * What a point on the map turned out to be.
 *
 * <p>Three things, and the third is the interesting one. The name and country
 * save a reader from typing what they just clicked. The extent is what lets the
 * map draw the city rather than a dot in the middle of it: a reader who has been
 * to Pokhara wants to see Pokhara, and a nine-pixel circle at the confluence of
 * two roads says much less.
 *
 * <p>The shape is the real boundary OpenStreetMap holds for whatever it
 * matched, as GeoJSON. Not a circle around the middle and not a bounding box:
 * the actual outline, so a reader who has been to Pokhara sees Pokhara and not
 * a blob over it.
 */
public class PlaceName implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String country;

    /** The boundary, as GeoJSON text, or null when there was none. */
    private String geometry;

    public PlaceName(String name, String country) {
        this.name = name;
        this.country = country;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getGeometry() {
        return geometry;
    }

    public void setGeometry(String geometry) {
        this.geometry = geometry;
    }
}
