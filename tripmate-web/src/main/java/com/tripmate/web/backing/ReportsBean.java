package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.CategorySpendingDTO;
import com.tripmate.dto.CategoryTotalDTO;
import com.tripmate.dto.DailyTotalDTO;
import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripTotalDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.web.chart.ChartPalette;
import com.tripmate.web.chart.Charts;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Where the money went, either across every trip or within one.
 *
 * <p>Two modes rather than two pages. With no {@code tripId} the page answers
 * "how do my trips compare, and what do I spend on"; with one it answers "what
 * did this trip cost and who is carrying it". They share the selector, the
 * insight panel and the chart styling, and splitting them into separate pages
 * would mean two of everything.
 *
 * <p>View-scoped, because switching between trips is a conversation with one
 * page rather than a series of fresh visits.
 */
@Named
@ViewScoped
public class ReportsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How many category slots exist before the rest fold into "Other". */
    private static final int MAX_SLICES = ChartPalette.size();

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    @EJB
    private ReportServiceRemote reportService;

    @EJB
    private TripServiceRemote tripService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @Inject
    private CurrentUser currentUser;

    /** The trip being reported on, or null for the all-trips view. */
    private Long tripId;

    private TripDTO trip;
    private List<TripDTO> trips = new ArrayList<>();

    private List<CategorySpendingDTO> categories = new ArrayList<>();
    private List<TripTotalDTO> tripTotals = new ArrayList<>();

    private List<CategoryTotalDTO> tripCategories = new ArrayList<>();
    private List<MemberBalanceDTO> memberBalances = new ArrayList<>();
    private List<DailyTotalDTO> dailyTotals = new ArrayList<>();

    private String categoryChart;
    private String trendChart;
    private String memberChart;
    private String tripChart;

    private List<String> insights = new ArrayList<>();
    private String loadError;

    /**
     * Loads whichever view the trip parameter selects.
     *
     * <p>Driven by {@code f:viewAction} rather than {@code @PostConstruct},
     * because at post-construct time the view parameter has not been applied.
     */
    public void load() {
        loadError = null;
        if (!currentUser.isLoggedIn()) {
            return;
        }

        Long userId = currentUser.getUserId();
        trips = new ArrayList<>(tripService.getTripsForUser(userId));

        if (tripId == null) {
            loadOverview(userId);
        } else {
            loadTrip(userId);
        }
    }

    // ------------------------------------------------------------------
    // The all-trips view
    // ------------------------------------------------------------------

    private void loadOverview(Long userId) {
        categories = new ArrayList<>(reportService.getCategorySpendingForUser(userId, userId));
        tripTotals = new ArrayList<>(reportService.getTripTotalsForUser(userId));

        categoryChart = Charts.doughnut(
                categories.stream().map(CategorySpendingDTO::getCategory).toList(),
                categories.stream().map(CategorySpendingDTO::getTotalSpent).toList(),
                ChartPalette.seriesColours(categories.size()));

        // Biggest trip first: an unordered bar chart makes the reader do the
        // sorting that the chart should have done.
        List<TripTotalDTO> ranked = tripTotals.stream()
                .sorted((a, b) -> b.getTotal().compareTo(a.getTotal()))
                .limit(8)
                .toList();

        tripChart = Charts.horizontalBar(
                ranked.stream().map(TripTotalDTO::getTripName).toList(),
                ranked.stream().map(TripTotalDTO::getTotal).toList(),
                ChartPalette.seriesColours(ranked.size()));

        insights = overviewInsights();
    }

    private List<String> overviewInsights() {
        List<String> found = new ArrayList<>();
        if (categories.isEmpty()) {
            return found;
        }

        BigDecimal total = categories.stream()
                .map(CategorySpendingDTO::getTotalSpent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CategorySpendingDTO largest = categories.get(0);
        found.add("Your largest category is " + largest.getCategory() + ", at "
                + percent(largest.getTotalSpent(), total) + " of everything you have spent.");

        found.add("You have spent across " + categories.size()
                + (categories.size() == 1 ? " category" : " categories")
                + ", on " + tripTotals.size()
                + (tripTotals.size() == 1 ? " trip." : " trips."));

        // The comparison that a single total cannot make.
        tripTotals.stream()
                .filter(t -> t.getMemberCount() != null && t.getMemberCount() > 1)
                .max((a, b) -> a.getPerPerson().compareTo(b.getPerPerson()))
                .ifPresent(t -> found.add("Your most expensive trip per head is "
                        + t.getTripName() + ", at NPR " + t.getPerPerson()
                        + " each across " + t.getMemberCount() + " people."));

        return found;
    }

    // ------------------------------------------------------------------
    // The single-trip view
    // ------------------------------------------------------------------

    private void loadTrip(Long userId) {
        trip = tripService.getTrip(tripId);
        if (trip == null) {
            loadError = "That trip does not exist, or it has been deleted.";
            return;
        }

        try {
            tripCategories = new ArrayList<>(reportService.getCategoryTotalsForTrip(tripId, userId));
            memberBalances = new ArrayList<>(reportService.getMemberBalances(tripId, userId));
            dailyTotals = new ArrayList<>(reportService.getDailyTotalsForTrip(tripId, userId));
        } catch (RuntimeException ex) {
            // The service refuses a non-member rather than returning nothing,
            // so a trip the reader cannot see is reported as such instead of
            // rendering as a trip with no spending.
            loadError = com.tripmate.web.support.Messages.describe(ex);
            trip = null;
            return;
        }

        categoryChart = Charts.doughnut(
                tripCategories.stream().map(CategoryTotalDTO::getLabel).toList(),
                tripCategories.stream().map(CategoryTotalDTO::getTotal).toList(),
                ChartPalette.seriesColours(tripCategories.size()));

        trendChart = dailyTotals.isEmpty() ? null : Charts.area(
                dailyTotals.stream().map(d -> d.getDay().format(DAY)).toList(),
                dailyTotals.stream().map(DailyTotalDTO::getTotal).toList(),
                ChartPalette.series(0));

        memberChart = memberBalances.isEmpty() ? null : Charts.groupedBar(
                memberBalances.stream().map(MemberBalanceDTO::getFullName).toList(),
                List.of("Paid", "Their share"),
                List.of(
                        memberBalances.stream().map(MemberBalanceDTO::getTotalPaid).toList(),
                        memberBalances.stream().map(MemberBalanceDTO::getTotalOwed).toList()),
                List.of(ChartPalette.series(0), ChartPalette.series(1)));

        insights = tripInsights(userId);
    }

    private List<String> tripInsights(Long userId) {
        List<String> found = new ArrayList<>();
        if (tripCategories.isEmpty()) {
            return found;
        }

        BigDecimal total = getTripTotal();
        CategoryTotalDTO largest = tripCategories.get(0);
        found.add(largest.getLabel() + " is the biggest cost, at NPR " + largest.getTotal()
                + " (" + percent(largest.getTotal(), total) + " of the trip).");

        if (dailyTotals.size() > 1) {
            DailyTotalDTO busiest = dailyTotals.stream()
                    .max((a, b) -> a.getTotal().compareTo(b.getTotal()))
                    .orElse(null);
            if (busiest != null) {
                found.add("The most expensive day was " + busiest.getDay().format(DAY)
                        + ", at NPR " + busiest.getTotal() + " across "
                        + busiest.getExpenseCount() + " expenses.");
            }
            found.add("Spending is spread over " + dailyTotals.size() + " days, averaging NPR "
                    + total.divide(BigDecimal.valueOf(dailyTotals.size()), 2, RoundingMode.HALF_UP)
                    + " a day.");
        }

        // The reader's own position, which is the question they actually came
        // with, phrased as a sentence rather than left in a table to be found.
        memberBalances.stream()
                .filter(m -> userId != null && userId.equals(m.getUserId()))
                .findFirst()
                .ifPresent(mine -> {
                    if (mine.isCreditor()) {
                        found.add("You are out of pocket on this trip. Once every share is "
                                + "counted you are owed NPR " + mine.getNetBalance() + ".");
                    } else if (mine.isDebtor()) {
                        found.add("You owe NPR " + mine.getNetBalance().abs()
                                + " on this trip once every share is counted.");
                    } else {
                        found.add("You are square on this trip: what you paid matches your share.");
                    }
                });

        return found;
    }

    // ------------------------------------------------------------------
    // Figures for the summary strip
    // ------------------------------------------------------------------

    /** @return everything spent on the trip, or zero */
    public BigDecimal getTripTotal() {
        return tripCategories.stream()
                .map(CategoryTotalDTO::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** @return what the trip cost per person, or zero when nobody is on it */
    public BigDecimal getTripPerPerson() {
        if (trip == null || trip.getMemberCount() <= 0) {
            return BigDecimal.ZERO;
        }
        return getTripTotal().divide(BigDecimal.valueOf(trip.getMemberCount()),
                2, RoundingMode.HALF_UP);
    }

    /** @return the total across every trip, for the overview tiles */
    public BigDecimal getTotalSpent() {
        return categories.stream()
                .map(CategorySpendingDTO::getTotalSpent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** @return the largest category in the overview, or null */
    public CategorySpendingDTO getLargest() {
        return categories.isEmpty() ? null : categories.get(0);
    }

    public long getCategoryCount() {
        return categories.size();
    }

    /** @return how many separate days the trip recorded spending on */
    public int getDaysWithSpending() {
        return dailyTotals.size();
    }

    /** @return how many expenses the trip's biggest day carried */
    public long getMostExpensiveDayCount() {
        return dailyTotals.stream()
                .max((a, b) -> a.getTotal().compareTo(b.getTotal()))
                .map(DailyTotalDTO::getExpenseCount)
                .orElse(0L);
    }

    /** @return the number of expenses recorded on the trip */
    public int getTripExpenseCount() {
        return tripCategories.stream()
                .mapToInt(c -> c.getExpenseCount() == null ? 0 : c.getExpenseCount().intValue())
                .sum();
    }

    /** @return the single largest expense on the trip, or null */
    public ExpenseDTO getBiggestExpense() {
        if (tripId == null) {
            return null;
        }
        return expenseService.getExpensesForTrip(tripId).stream()
                .max((a, b) -> a.getAmount().compareTo(b.getAmount()))
                .orElse(null);
    }

    /**
     * A share of a total, as a whole number of percent.
     *
     * @return zero when the total is zero, so a caller never divides by it
     */
    private static String percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return "0%";
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 0, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    /**
     * @return the user's own row in the trip's balances, or null when there is
     *         no trip or they are not on it
     */
    public MemberBalanceDTO getMyPosition() {
        Long userId = currentUser.getUserId();
        return memberBalances.stream()
                .filter(m -> userId != null && userId.equals(m.getUserId()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public TripDTO getTrip() {
        return trip;
    }

    public List<TripDTO> getTrips() {
        return trips;
    }

    public boolean isTripView() {
        return tripId != null && trip != null;
    }

    public List<CategorySpendingDTO> getCategories() {
        return categories;
    }

    public List<TripTotalDTO> getTripTotals() {
        return tripTotals;
    }

    public List<CategoryTotalDTO> getTripCategories() {
        return tripCategories;
    }

    public List<MemberBalanceDTO> getMemberBalances() {
        return memberBalances;
    }

    public String getCategoryChart() {
        return categoryChart;
    }

    public String getTrendChart() {
        return trendChart;
    }

    public String getMemberChart() {
        return memberChart;
    }

    public String getTripChart() {
        return tripChart;
    }

    public List<String> getInsights() {
        return insights;
    }

    public boolean isHasInsights() {
        return !insights.isEmpty();
    }

    public String getLoadError() {
        return loadError;
    }

    public boolean isHasData() {
        return tripId == null ? !categories.isEmpty() : !tripCategories.isEmpty();
    }
}
