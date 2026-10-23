package com.tripmate.web.chart;

import java.math.BigDecimal;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Builds the Chart.js configuration for the report page.
 *
 * <p>PrimeFaces 14's {@code p:chart} takes a raw JSON {@code value}, not the
 * XDev model earlier versions used. There is no {@code model} attribute on the
 * tag at all, and an unknown attribute is ignored rather than reported, so
 * passing a model produces a chart that renders an empty canvas with nothing in
 * the log to explain it.
 *
 * <p>Everything is built with JSON-P, which ships with Jakarta EE, rather than
 * by concatenating strings: category names and trip names come from data a
 * person typed.
 *
 * <h2>What is deliberately absent</h2>
 *
 * Tooltips show the raw figure rather than a formatted one. Chart.js accepts a
 * formatting function, and a function cannot be serialised into JSON, so
 * producing "NPR 25,000.00" in a tooltip would mean emitting JavaScript into a
 * data attribute and evaluating it. The figures a reader checks are in the
 * table beside every chart, kept as {@link BigDecimal} the whole way from the
 * query, and the tooltip is a pointer rather than the source of truth.
 */
public final class Charts {

    /**
     * How much of the doughnut is hollowed out.
     *
     * <p>A ring rather than a pie. The hole is where the total goes, and a
     * reader can compare arc lengths more reliably than wedge areas.
     */
    private static final String DOUGHNUT_CUTOUT = "62%";

    private Charts() {
    }

    /**
     * A ring, for parts of a whole.
     *
     * @param labels  the segment names, in the order of {@code values}
     * @param values  the segment sizes
     * @param colours one colour per segment, from {@link ChartPalette}
     */
    public static String doughnut(List<String> labels, List<BigDecimal> values, List<String> colours) {
        JsonObject dataSet = Json.createObjectBuilder()
                .add("data", numbers(values))
                .add("backgroundColor", strings(colours))
                // A ring of touching segments reads as one shape. A two-pixel
                // gap in the surface colour separates them instead.
                .add("borderColor", ChartPalette.SURFACE)
                .add("borderWidth", 2)
                .add("hoverOffset", 6)
                .build();

        JsonObject config = Json.createObjectBuilder()
                .add("type", "doughnut")
                .add("data", Json.createObjectBuilder()
                        .add("labels", strings(labels))
                        .add("datasets", Json.createArrayBuilder().add(dataSet)))
                .add("options", Json.createObjectBuilder()
                        .add("responsive", true)
                        .add("maintainAspectRatio", false)
                        .add("cutout", DOUGHNUT_CUTOUT)
                        .add("plugins", Json.createObjectBuilder()
                                .add("legend", hidden())))
                .build();
        return config.toString();
    }

    /**
     * A filled line, for a value over time.
     *
     * <p>The fill is a wash of the line's own colour rather than a second
     * colour, so the series still reads as one thing.
     */
    public static String area(List<String> labels, List<BigDecimal> values, String colour) {
        JsonObject dataSet = Json.createObjectBuilder()
                .add("data", numbers(values))
                .add("borderColor", colour)
                // Thin marks: a two-pixel stroke, not a slab.
                .add("borderWidth", 2)
                .add("backgroundColor", ChartPalette.alpha(colour, 0.12))
                .add("fill", true)
                .add("tension", 0.3)
                .add("pointBackgroundColor", colour)
                .add("pointBorderColor", ChartPalette.SURFACE)
                .add("pointBorderWidth", 2)
                // Eight pixels is the floor at which a marker is a target
                // rather than a speck.
                .add("pointRadius", 4)
                .add("pointHoverRadius", 6)
                .build();

        JsonObject config = Json.createObjectBuilder()
                .add("type", "line")
                .add("data", Json.createObjectBuilder()
                        .add("labels", strings(labels))
                        .add("datasets", Json.createArrayBuilder().add(dataSet)))
                .add("options", cartesian(false))
                .build();
        return config.toString();
    }

    /**
     * Grouped bars, for comparing two measures across the same categories.
     *
     * <p>Grouped rather than stacked: the two series are paid and share, and
     * stacking them would sum to something meaningless. Side by side, the gap
     * between them is the thing being read.
     *
     * @param labels      one per group along the axis
     * @param seriesNames the legend names, in order
     * @param seriesData  one list of values per series, each as long as
     *                    {@code labels}
     * @param colours     one colour per series
     */
    public static String groupedBar(List<String> labels, List<String> seriesNames,
                                    List<List<BigDecimal>> seriesData, List<String> colours) {
        JsonArrayBuilder datasets = Json.createArrayBuilder();
        for (int i = 0; i < seriesNames.size(); i++) {
            datasets.add(Json.createObjectBuilder()
                    .add("label", seriesNames.get(i))
                    .add("data", numbers(seriesData.get(i)))
                    .add("backgroundColor", colours.get(i))
                    .add("borderColor", ChartPalette.SURFACE)
                    .add("borderWidth", 2)
                    .add("borderRadius", 4)
                    .add("maxBarThickness", 42));
        }

        JsonObject config = Json.createObjectBuilder()
                .add("type", "bar")
                .add("data", Json.createObjectBuilder()
                        .add("labels", strings(labels))
                        .add("datasets", datasets))
                .add("options", cartesian(true))
                .build();
        return config.toString();
    }

    /**
     * Horizontal bars, for comparing a measure across named things.
     *
     * <p>Horizontal because the labels are trip names, which are long. Rotated
     * vertical labels are the usual alternative and they are unreadable.
     */
    public static String horizontalBar(List<String> labels, List<BigDecimal> values, List<String> colours) {
        JsonObject dataSet = Json.createObjectBuilder()
                .add("data", numbers(values))
                .add("backgroundColor", strings(colours))
                .add("borderRadius", 4)
                .add("maxBarThickness", 28)
                .build();

        JsonObject scales = Json.createObjectBuilder()
                .add("x", Json.createObjectBuilder()
                        .add("beginAtZero", true)
                        .add("grid", Json.createObjectBuilder().add("color", ChartPalette.GRID))
                        .add("ticks", tickStyle()))
                .add("y", Json.createObjectBuilder()
                        .add("grid", Json.createObjectBuilder().add("display", false))
                        .add("ticks", tickStyle()))
                .build();

        JsonObject config = Json.createObjectBuilder()
                .add("type", "bar")
                .add("data", Json.createObjectBuilder()
                        .add("labels", strings(labels))
                        .add("datasets", Json.createArrayBuilder().add(dataSet)))
                .add("options", Json.createObjectBuilder()
                        .add("indexAxis", "y")
                        .add("responsive", true)
                        .add("maintainAspectRatio", false)
                        .add("plugins", Json.createObjectBuilder()
                                .add("legend", hidden())
                                .add("tooltip", tooltip()))
                        .add("scales", scales))
                .build();
        return config.toString();
    }

    // ------------------------------------------------------------------
    // Shared option blocks
    // ------------------------------------------------------------------

    /** Options for a chart with an x and a y axis. */
    private static JsonObject cartesian(boolean showLegend) {
        JsonObjectBuilder legend = Json.createObjectBuilder()
                .add("display", showLegend)
                .add("position", "bottom")
                .add("labels", Json.createObjectBuilder()
                        // A box legend, because the swatch is what ties a colour
                        // to a name. Chart.js draws its own legend on the canvas
                        // and this one is used only when a chart genuinely needs
                        // both series named at once.
                        .add("usePointStyle", true)
                        .add("boxWidth", 8)
                        .add("boxHeight", 8)
                        .add("color", ChartPalette.AXIS)
                        .add("padding", 16));

        JsonObject scales = Json.createObjectBuilder()
                .add("x", Json.createObjectBuilder()
                        .add("grid", Json.createObjectBuilder().add("display", false))
                        .add("ticks", tickStyle()))
                .add("y", Json.createObjectBuilder()
                        .add("beginAtZero", true)
                        .add("grid", Json.createObjectBuilder().add("color", ChartPalette.GRID))
                        // Horizontal rules only. A full grid is noise behind
                        // thin marks.
                        .add("border", Json.createObjectBuilder().add("display", false))
                        .add("ticks", tickStyle()))
                .build();

        return Json.createObjectBuilder()
                .add("responsive", true)
                .add("maintainAspectRatio", false)
                .add("interaction", Json.createObjectBuilder()
                        .add("mode", "index")
                        .add("intersect", false))
                .add("plugins", Json.createObjectBuilder()
                        .add("legend", legend)
                        .add("tooltip", tooltip()))
                .add("scales", scales)
                .build();
    }

    private static JsonObjectBuilder tickStyle() {
        return Json.createObjectBuilder()
                .add("color", ChartPalette.AXIS)
                .add("font", Json.createObjectBuilder().add("size", 11));
    }

    private static JsonObjectBuilder tooltip() {
        return Json.createObjectBuilder()
                .add("backgroundColor", ChartPalette.INK)
                .add("padding", 10)
                .add("cornerRadius", 8)
                .add("displayColors", true)
                .add("boxWidth", 8)
                .add("boxHeight", 8);
    }

    private static JsonObjectBuilder hidden() {
        return Json.createObjectBuilder().add("display", false);
    }

    private static JsonArray numbers(List<BigDecimal> values) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (BigDecimal value : values) {
            // The one place in the application where the exact decimal type
            // does not matter: a chart is drawn on a canvas a few hundred
            // pixels wide, and the figures a reader checks are in the table
            // beside it, which keeps BigDecimal all the way from the query.
            array.add(value == null ? 0d : value.doubleValue());
        }
        return array.build();
    }

    private static JsonArray strings(List<String> values) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (String value : values) {
            array.add(value == null ? "" : value);
        }
        return array.build();
    }
}
