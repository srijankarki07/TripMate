package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.remote.ReportServiceRemote;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;

/**
 * Where the signed-in user's money went, by category.
 *
 * <p>The figures come from query 5, which groups in the database across every
 * trip the user belongs to. The chart is a summary of the same numbers shown in
 * the table beneath it, not a substitute for them: the table is what someone
 * reads to find out an exact figure.
 */
@Named
@RequestScoped
public class ReportsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The chart's bar colour. Rausch, the system's single accent.
     *
     * <p>A chart is the one place several colours would normally be used, but
     * there is only one series here and DESIGN.md has one accent, so the bars
     * take it and the bars' labels carry the distinction instead.
     */
    private static final String BAR_COLOUR = "#ff385c";

    @EJB
    private ReportServiceRemote reportService;

    @Inject
    private CurrentUser currentUser;

    private List<CategorySpendingDTO> categories = List.of();
    private String chartJson;

    @PostConstruct
    void load() {
        if (!currentUser.isLoggedIn()) {
            return;
        }
        categories = reportService.getCategorySpendingForUser(currentUser.getUserId());
        chartJson = buildChartJson();
    }

    /**
     * Builds the Chart.js configuration as JSON.
     *
     * <p>PrimeFaces 14's {@code p:chart} takes a raw JSON {@code value}, not the
     * XDev model the earlier versions used. There is no {@code model} attribute
     * on the tag at all, and an unknown attribute is ignored rather than
     * reported, so passing a model produces a chart that renders an empty
     * canvas with nothing in the log to explain it.
     *
     * <p>Built with JSON-P, which ships with Jakarta EE, rather than by
     * concatenating strings: the category names come from user-entered data.
     *
     * <p>The figures are converted to doubles. This is the one place in the
     * application where the exact decimal type does not matter, because the
     * figures a reader checks are in the table below, which keeps BigDecimal
     * all the way from the query.
     */
    private String buildChartJson() {
        JsonArrayBuilder labels = Json.createArrayBuilder();
        JsonArrayBuilder values = Json.createArrayBuilder();
        for (CategorySpendingDTO row : categories) {
            labels.add(row.getCategory() == null ? "Uncategorised" : row.getCategory());
            values.add(row.getTotalSpent().doubleValue());
        }

        JsonObject dataSet = Json.createObjectBuilder()
                .add("label", "Your share")
                .add("data", values)
                .add("backgroundColor", BAR_COLOUR)
                .add("borderColor", BAR_COLOUR)
                .build();

        JsonObject config = Json.createObjectBuilder()
                .add("type", "bar")
                .add("data", Json.createObjectBuilder()
                        .add("labels", labels)
                        .add("datasets", Json.createArrayBuilder().add(dataSet)))
                .add("options", Json.createObjectBuilder()
                        .add("responsive", true)
                        .add("maintainAspectRatio", false)
                        // One series, so a legend would only repeat the axis.
                        .add("plugins", Json.createObjectBuilder()
                                .add("legend", Json.createObjectBuilder().add("display", false))))
                .build();

        return config.toString();
    }

    // ------------------------------------------------------------------
    // Figures for the summary strip
    // ------------------------------------------------------------------

    public BigDecimal getTotalSpent() {
        return categories.stream()
                .map(CategorySpendingDTO::getTotalSpent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The largest category, or null when there is nothing to report. */
    public CategorySpendingDTO getLargest() {
        return categories.isEmpty() ? null : categories.get(0);
    }

    public long getCategoryCount() {
        return categories.size();
    }

    /**
     * The largest category as a percentage of the whole, for the summary note.
     *
     * @return a whole number of percent, or zero when there is nothing to report
     */
    public int getLargestSharePercent() {
        BigDecimal total = getTotalSpent();
        if (categories.isEmpty() || total.signum() == 0) {
            return 0;
        }
        return categories.get(0).getTotalSpent()
                .multiply(BigDecimal.valueOf(100))
                .divide(total, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public List<CategorySpendingDTO> getCategories() {
        return categories;
    }

    public String getChartJson() {
        return chartJson;
    }

    public boolean isHasData() {
        return !categories.isEmpty();
    }
}
