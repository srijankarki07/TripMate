package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Who is up and who is down on a trip.
 *
 * <p>The balances come from query 4, which reports the gross position: every
 * split counts, and recorded repayments are not netted off. That is the figure
 * the balances tab shows. Alongside it this page suggests the smallest set of
 * payments that would settle the trip, which is a different question and is
 * computed here rather than in the database.
 */
@Named
@ViewScoped
public class BalancesBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private ReportServiceRemote reportService;

    @EJB
    private ExpenseServiceRemote expenseService;

    @EJB
    private TripServiceRemote tripService;

    @Inject
    private CurrentUser currentUser;

    private Long tripId;
    private TripDTO trip;
    private List<MemberBalanceDTO> balances = List.of();
    private List<SettlementDTO> settlements = List.of();
    private List<Transfer> suggested = List.of();

    private boolean member;
    private String error;

    public void load() {
        error = null;
        if (tripId == null) {
            error = "No trip was named.";
            return;
        }
        trip = tripService.getTrip(tripId);
        if (trip == null) {
            error = "That trip does not exist, or it has been deleted.";
            return;
        }
        member = tripService.isMember(tripId, currentUser.getUserId());
        if (!member) {
            error = "You are not a member of this trip.";
            trip = null;
            return;
        }

        balances = reportService.getMemberBalances(tripId);
        settlements = expenseService.getSettlementsForTrip(tripId);
        suggested = suggestTransfers(balances);
    }

    /**
     * Works out a short list of payments that would clear the trip.
     *
     * <p>Repeatedly matches the largest debtor against the largest creditor and
     * transfers the smaller of the two. That greedy pass does not always give
     * the theoretical minimum number of payments, but it never suggests a
     * payment larger than someone actually owes, and it settles the trip in at
     * most one payment fewer than there are people.
     *
     * <p>This is a suggestion, not a record. Nothing is written until someone
     * submits the settle form, because a repayment is a thing that happened
     * rather than a thing the system decided.
     */
    static List<Transfer> suggestTransfers(List<MemberBalanceDTO> balances) {
        List<MemberBalanceDTO> debtors = new ArrayList<>(balances.stream()
                .filter(MemberBalanceDTO::isDebtor)
                .toList());
        List<MemberBalanceDTO> creditors = new ArrayList<>(balances.stream()
                .filter(MemberBalanceDTO::isCreditor)
                .toList());

        // Largest first, so the biggest imbalances are cleared in one go.
        debtors.sort(Comparator.comparing(b -> b.getNetBalance().abs(), Comparator.reverseOrder()));
        creditors.sort(Comparator.comparing(MemberBalanceDTO::getNetBalance, Comparator.reverseOrder()));

        List<Transfer> transfers = new ArrayList<>();
        int d = 0;
        int c = 0;
        BigDecimal[] owed = debtors.stream()
                .map(b -> b.getNetBalance().abs())
                .toArray(BigDecimal[]::new);
        BigDecimal[] due = creditors.stream()
                .map(MemberBalanceDTO::getNetBalance)
                .toArray(BigDecimal[]::new);

        while (d < debtors.size() && c < creditors.size()) {
            BigDecimal amount = owed[d].min(due[c]);
            if (amount.signum() > 0) {
                transfers.add(new Transfer(
                        debtors.get(d).getFullName(),
                        creditors.get(c).getFullName(),
                        amount));
            }
            owed[d] = owed[d].subtract(amount);
            due[c] = due[c].subtract(amount);
            if (owed[d].signum() == 0) {
                d++;
            }
            if (due[c].signum() == 0) {
                c++;
            }
        }
        return transfers;
    }

    /**
     * One suggested payment.
     *
     * <p>A class with getters rather than a record: EL 5 resolves bean
     * properties only, so a record's accessors are not properties.
     */
    public static final class Transfer implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String from;
        private final String to;
        private final BigDecimal amount;

        Transfer(String from, String to, BigDecimal amount) {
            this.from = from;
            this.to = to;
            this.amount = amount;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    // ------------------------------------------------------------------
    // Totals for the summary strip
    // ------------------------------------------------------------------

    public BigDecimal getTotalOwed() {
        return balances.stream()
                .filter(MemberBalanceDTO::isCreditor)
                .map(MemberBalanceDTO::getNetBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalRepaid() {
        return settlements.stream()
                .map(SettlementDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long getCreditorCount() {
        return balances.stream().filter(MemberBalanceDTO::isCreditor).count();
    }

    public boolean isSettled() {
        return balances.stream().noneMatch(MemberBalanceDTO::isDebtor);
    }

    public boolean isHasSuggestions() {
        return !suggested.isEmpty();
    }

    public boolean isHasSettlements() {
        return !settlements.isEmpty();
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

    public List<MemberBalanceDTO> getBalances() {
        return balances;
    }

    public List<SettlementDTO> getSettlements() {
        return settlements;
    }

    public List<Transfer> getSuggested() {
        return suggested;
    }

    public boolean isMember() {
        return member;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    public String getError() {
        return error;
    }
}
