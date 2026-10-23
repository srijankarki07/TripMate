package com.tripmate.web.chart;

import java.util.List;

/**
 * The colours charts are drawn in.
 *
 * <h2>Why this is Java and not CSS</h2>
 *
 * PrimeFaces draws charts with Chart.js, onto a canvas. Nothing inside the plot
 * is reachable from a stylesheet, so the series colours, the grid lines and the
 * axis labels all have to be handed over in the JSON configuration, which is
 * built here. The {@code --tm-chart-*} custom properties in {@code tripmate.css}
 * exist for the HTML around the canvas, the legend and the swatches, and are the
 * same six values.
 *
 * <p>Two copies of one palette can drift, so the diagnostics page compares them
 * and reports it rather than letting a chart quietly come out in the wrong
 * colours. When changing a colour here, change it there too.
 *
 * <h2>Why the order matters</h2>
 *
 * The slot order is the colourblind-safety mechanism, not a preference. It was
 * chosen by validating candidate orderings against lightness, chroma, adjacent
 * pair separation under simulated protanopia and deuteranopia, normal-vision
 * separation, and contrast against a white surface. The current order passes all
 * of those with no warnings. Reordering the slots, or cycling them for a seventh
 * series, breaks that and is not a cosmetic change.
 *
 * <p>See DESIGN.md Part Two, section 5.
 */
public final class ChartPalette {

    /**
     * The categorical slots, in their validated order.
     *
     * <p>Assign from the top and do not wrap around. A seventh category folds
     * into {@link #OTHER} rather than taking a generated hue.
     */
    private static final List<String> SERIES = List.of(
            "#ff385c",  // 1 Rausch, the brand tone
            "#2a78d6",  // 2 blue
            "#12916a",  // 3 green
            "#c98500",  // 4 amber
            "#4a3aa7",  // 5 violet
            "#d55181"); // 6 magenta

    /**
     * The neutral reserved for "Other".
     *
     * <p>Never handed out as a series colour. It reads as an absence rather than
     * as another category, which is what the seventh slot usually is.
     */
    public static final String OTHER = "#6a6a6a";

    /** Grid lines, deliberately recessive so the data sits in front of them. */
    public static final String GRID = "#ebebeb";

    /** Axis labels and tick text. */
    public static final String AXIS = "#6a6a6a";

    /** Text inside the plot: tooltips and value labels. */
    public static final String INK = "#222222";

    /** The chart surface, used for the gap between stacked segments. */
    public static final String SURFACE = "#ffffff";

    private ChartPalette() {
    }

    /**
     * The colour for a slot.
     *
     * @param index the slot, counted from zero as the palette is ordered
     * @return the colour, or {@link #OTHER} once the slots run out
     */
    public static String series(int index) {
        return index < SERIES.size() ? SERIES.get(index) : OTHER;
    }

    /** @return every categorical slot, in order */
    public static List<String> series() {
        return SERIES;
    }

    /** @return how many categorical slots there are before "Other" begins */
    public static int size() {
        return SERIES.size();
    }

    /**
     * The colour for each of a list of categories, in order.
     *
     * <p>Past the last slot every remaining category takes the neutral. The
     * caller is expected to have already folded them into a single "Other"
     * entry, so in practice this returns the neutral once.
     *
     * @param count how many categories need a colour
     */
    public static List<String> seriesColours(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ChartPalette::series)
                .toList();
    }

    /**
     * A translucent version of a colour, for area fills under a line.
     *
     * <p>Chart.js accepts {@code rgba()}, and a fill at full strength would hide
     * the grid and dominate the chart it is meant to support.
     *
     * @param hex   a six-digit hex colour
     * @param alpha the opacity, between zero and one
     */
    public static String alpha(String hex, double alpha) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        int r = Integer.parseInt(value.substring(0, 2), 16);
        int g = Integer.parseInt(value.substring(2, 4), 16);
        int b = Integer.parseInt(value.substring(4, 6), 16);
        return "rgba(" + r + ", " + g + ", " + b + ", " + alpha + ")";
    }
}
