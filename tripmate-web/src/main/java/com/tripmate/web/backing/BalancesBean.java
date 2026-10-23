package com.tripmate.web.backing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;
import com.tripmate.web.support.Initials;
import com.tripmate.web.support.Messages;
import com.tripmate.web.support.Toasts;

import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Who is up and who is down on a trip, and what would settle it.
 *
 * <h2>Two different questions, shown separately</h2>
 *
 * Query 4 reports the <strong>gross</strong> position: what each member has paid
 * against what their splits come to, counting every split whether or not it has
 * been repaid. It does not know about the settlements table.
 *
 * <p>That is not the question someone asks before paying somebody. They ask what
 * is <em>still</em> outstanding, which has to account for the repayments already
 * made. On the seeded Pokhara trip the difference is not academic: the gross
 * figures say Carol owes 9,225, and she has already paid Alice 1,000 of it.
 *
 * <p>So this page computes both and labels them. The suggestion is built from
 * the outstanding figures, because suggesting a payment larger than what is
 * actually left to pay is exactly the mistake the settle-up flow exists to
 * avoid.
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
    private List<MemberPosition> positions = new ArrayList<>();
    private List<SettlementDTO> settlements = new ArrayList<>();
    private List<Transfer> suggested = new ArrayList<>();

    private boolean member;
    public void load() {
        loadError = null;
        if (tripId == null) {
            loadError = "No trip was named.";
            return;
        }
        trip = tripService.getTrip(tripId);
        if (trip == null) {
            loadError = "That trip does not exist, or it has been deleted.";
            return;
        }
        member = tripService.isMember(tripId, currentUser.getUserId());
        if (!member) {
            loadError = "You are not a member of this trip.";
            trip = null;
            return;
        }

        List<MemberBalanceDTO> balances = reportService.getMemberBalances(tripId, currentUser.getUserId());
        settlements = new ArrayList<>(expenseService.getSettlementsForTrip(tripId));
        positions = new ArrayList<>(buildPositions(balances, settlements));
        suggested = new ArrayList<>(suggestTransfers(positions));
    }

    /**
     * Combines the query 4 figures with the settlements into one row per member.
     *
     * <p>The arithmetic is a cash ledger. For each person:
     *
     * <pre>
     *   net         = paid - share          (negative means they underpaid)
     *   outstanding = net + repaid out - received
     * </pre>
     *
     * <p>Outstanding is therefore what still has to move for everyone to be
     * square: negative means they still pay, positive means they are still owed.
     * The column sums to zero, because every settlement is one person's payment
     * and another's receipt.
     */
    static List<MemberPosition> buildPositions(List<MemberBalanceDTO> balances,
                                               List<SettlementDTO> settlements) {
        Map<Long, BigDecimal> repaidOut = new HashMap<>();
        Map<Long, BigDecimal> received = new HashMap<>();
        for (SettlementDTO settlement : settlements) {
            repaidOut.merge(settlement.getPayerId(), settlement.getAmount(), BigDecimal::add);
            received.merge(settlement.getReceiverId(), settlement.getAmount(), BigDecimal::add);
        }

        List<MemberPosition> result = new ArrayList<>();
        for (MemberBalanceDTO balance : balances) {
            BigDecimal paid = balance.getTotalPaid();
            BigDecimal share = balance.getTotalOwed();
            BigDecimal net = paid.subtract(share);
            BigDecimal out = repaidOut.getOrDefault(balance.getUserId(), BigDecimal.ZERO);
            BigDecimal in = received.getOrDefault(balance.getUserId(), BigDecimal.ZERO);

            result.add(new MemberPosition(
                    balance.getUserId(),
                    balance.getFullName(),
                    paid,
                    share,
                    net,
                    out,
                    in,
                    net.add(out).subtract(in)));
        }
        return result;
    }

    /**
     * Works out a short list of payments that would clear the trip.
     *
     * <p>Repeatedly matches the largest debtor against the largest creditor and
     * transfers the smaller of the two. That greedy pass does not always give
     * the theoretical minimum number of payments, but it never suggests a
     * payment larger than the person actually has left to pay, which is the
     * property that matters.
     *
     * <p>Built from the outstanding figures, not the gross ones, so a repayment
     * already made is not asked for twice.
     *
     * <p>This is a suggestion, not a record. Nothing is written until someone
     * submits the settle form, because a repayment is a thing that happened
     * rather than a thing the system decided.
     */
    static List<Transfer> suggestTransfers(List<MemberPosition> positions) {
        List<MemberPosition> debtors = new ArrayList<>(positions.stream()
                .filter(MemberPosition::isDebtor)
                .toList());
        List<MemberPosition> creditors = new ArrayList<>(positions.stream()
                .filter(MemberPosition::isCreditor)
                .toList());

        // Largest first, so the biggest imbalances clear in one go.
        debtors.sort(Comparator.comparing(MemberPosition::getOutstandingMagnitude,
                Comparator.reverseOrder()));
        creditors.sort(Comparator.comparing(MemberPosition::getOutstanding,
                Comparator.reverseOrder()));

        BigDecimal[] owed = debtors.stream()
                .map(MemberPosition::getOutstandingMagnitude)
                .toArray(BigDecimal[]::new);
        BigDecimal[] due = creditors.stream()
                .map(MemberPosition::getOutstanding)
                .toArray(BigDecimal[]::new);

        List<Transfer> transfers = new ArrayList<>();
        int d = 0;
        int c = 0;
        while (d < debtors.size() && c < creditors.size()) {
            BigDecimal amount = owed[d].min(due[c]);
            if (amount.signum() > 0) {
                transfers.add(new Transfer(
                        debtors.get(d).getUserId(),
                        debtors.get(d).getFullName(),
                        creditors.get(c).getUserId(),
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
     * One member's position.
     *
     * <p>A class with getters rather than a record: EL 5 resolves bean
     * properties only, so a record's accessors are not properties.
     */
    public static final class MemberPosition implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Long userId;
        private final String fullName;
        private final BigDecimal paid;
        private final BigDecimal share;
        private final BigDecimal net;
        private final BigDecimal repaidOut;
        private final BigDecimal received;
        private final BigDecimal outstanding;

        MemberPosition(Long userId, String fullName, BigDecimal paid, BigDecimal share,
                       BigDecimal net, BigDecimal repaidOut, BigDecimal received,
                       BigDecimal outstanding) {
            this.userId = userId;
            this.fullName = fullName;
            // Every amount is normalised to two decimal places here, so a
            // column of money reads as money: BigDecimal.ZERO has a scale of
            // zero and would otherwise render as "NPR 0" beside "NPR 1200.00".
            this.paid = money(paid);
            this.share = money(share);
            this.net = money(net);
            this.repaidOut = money(repaidOut);
            this.received = money(received);
            this.outstanding = money(outstanding);
        }

        private static BigDecimal money(BigDecimal value) {
            return value == null ? BigDecimal.ZERO.setScale(2)
                    : value.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        public Long getUserId() {
            return userId;
        }

        public String getFullName() {
            return fullName;
        }

        /** @return the avatar letters for this member, from the shared rule */
        public String getInitials() {
            return Initials.of(fullName);
        }

        public BigDecimal getPaid() {
            return paid;
        }

        /** What their splits come to: the share of the trip they are on the hook for. */
        public BigDecimal getShare() {
            return share;
        }

        /** Paid minus share. Negative means they underpaid. */
        public BigDecimal getNet() {
            return net;
        }

        public BigDecimal getRepaidOut() {
            return repaidOut;
        }

        public BigDecimal getReceived() {
            return received;
        }

        /** What still has to move. Negative means they still pay. */
        public BigDecimal getOutstanding() {
            return outstanding;
        }

        public BigDecimal getOutstandingMagnitude() {
            return outstanding.abs();
        }

        public boolean isDebtor() {
            return outstanding.signum() < 0;
        }

        public boolean isCreditor() {
            return outstanding.signum() > 0;
        }

        public boolean isSquare() {
            return outstanding.signum() == 0;
        }

        /** True when no repayment has touched this member yet. */
        public boolean isUntouchedBySettlements() {
            return repaidOut.signum() == 0 && received.signum() == 0;
        }
    }

    /**
     * One suggested payment.
     *
     * <p>Carries both the ids and the names. The names are what the table
     * prints; the ids are what the settle page needs to preselect the two
     * people when the reader arrives from a row of the balances table, and a
     * name is not a reliable way to find somebody again.
     */
    public static final class Transfer implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Long fromId;
        private final String from;
        private final Long toId;
        private final String to;
        private final BigDecimal amount;

        Transfer(Long fromId, String from, Long toId, String to, BigDecimal amount) {
            this.fromId = fromId;
            this.from = from;
            this.toId = toId;
            this.to = to;
            this.amount = amount;
        }

        public Long getFromId() {
            return fromId;
        }

        public String getFrom() {
            return from;
        }

        public Long getToId() {
            return toId;
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

    /** Everything still owed to somebody, after the repayments already made. */
    public BigDecimal getTotalOutstanding() {
        return positions.stream()
                .filter(MemberPosition::isCreditor)
                .map(MemberPosition::getOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalRepaid() {
        return settlements.stream()
                .map(SettlementDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long getDebtorCount() {
        return positions.stream().filter(MemberPosition::isDebtor).count();
    }

    public boolean isSettled() {
        return positions.stream().noneMatch(MemberPosition::isDebtor);
    }

    public boolean isHasSuggestions() {
        return !suggested.isEmpty();
    }

    public boolean isHasSettlements() {
        return !settlements.isEmpty();
    }

    /** True when any repayment has been made, so the two figures differ. */
    public boolean isRepaymentsAffectFigures() {
        return positions.stream().anyMatch(p -> !p.isUntouchedBySettlements());
    }

    // ------------------------------------------------------------------
    // The position bar
    // ------------------------------------------------------------------

    /** The largest distance from square that anybody on the trip is. */
    public BigDecimal getLargestPosition() {
        return positions.stream()
                .map(MemberPosition::getOutstandingMagnitude)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * How far along its half of the bar one member's position reaches, as a
     * whole number of percent from 0 to 100.
     *
     * <p>Every member is scaled against the same figure, the largest position on
     * the trip. That is the point of the bar: scaling each row to its own maximum
     * would draw every position the same length and say nothing, and scaling to
     * the trip total would draw a single large position as a full bar with
     * everybody else as a sliver.
     *
     * <p>The floor of 4 keeps a position that is real but tiny visible as a mark
     * rather than as nothing at all, and a square position returns 0 so it draws
     * no bar and the centre tick stands alone.
     *
     * @param position the member's row, which the view passes in
     * @return 0 to 100
     */
    public int barWidth(MemberPosition position) {
        BigDecimal largest = getLargestPosition();
        if (position == null || largest.signum() == 0) {
            return 0;
        }
        BigDecimal magnitude = position.getOutstandingMagnitude();
        if (magnitude.signum() == 0) {
            return 0;
        }
        int percent = magnitude.multiply(BigDecimal.valueOf(100))
                .divide(largest, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(4, Math.min(100, percent));
    }

    /** @return true when there is a position worth drawing a bar for */
    public boolean isHasPositionBar() {
        return !positions.isEmpty() && getLargestPosition().signum() > 0;
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

    /**
     * The list itself, deliberately, and mutable.
     *
     * <p>PrimeFaces sorts a table by sorting the collection this returns, in
     * place, and then reads it again to render. Returning a defensive copy
     * looks safer and silently breaks sorting: the sort lands on a copy that is
     * thrown away, and the render reads a fresh unsorted one. Returning one of
     * the immutable {@code List.of()} defaults instead throws
     * {@code UnsupportedOperationException} inside the render, which leaves the
     * table empty with nothing in the log to explain it.
     *
     * <p>So the field is a real {@code ArrayList} and is handed over as-is. The
     * sort mutating it is the intended contract, not an accident.
     *
     * @return the rows, which the table is expected to reorder
     */
    public List<MemberPosition> getPositions() {
        return positions;
    }

    /**
     * The list itself, deliberately, and mutable.
     *
     * <p>PrimeFaces sorts a table by sorting the collection this returns, in
     * place, and then reads it again to render. Returning a defensive copy
     * looks safer and silently breaks sorting: the sort lands on a copy that is
     * thrown away, and the render reads a fresh unsorted one. Returning one of
     * the immutable {@code List.of()} defaults instead throws
     * {@code UnsupportedOperationException} inside the render, which leaves the
     * table empty with nothing in the log to explain it.
     *
     * <p>So the field is a real {@code ArrayList} and is handed over as-is. The
     * sort mutating it is the intended contract, not an accident.
     *
     * @return the rows, which the table is expected to reorder
     */
    public List<SettlementDTO> getSettlements() {
        return settlements;
    }

    /**
     * The list itself, deliberately, and mutable.
     *
     * <p>PrimeFaces sorts a table by sorting the collection this returns, in
     * place, and then reads it again to render. Returning a defensive copy
     * looks safer and silently breaks sorting: the sort lands on a copy that is
     * thrown away, and the render reads a fresh unsorted one. Returning one of
     * the immutable {@code List.of()} defaults instead throws
     * {@code UnsupportedOperationException} inside the render, which leaves the
     * table empty with nothing in the log to explain it.
     *
     * <p>So the field is a real {@code ArrayList} and is handed over as-is. The
     * sort mutating it is the intended contract, not an accident.
     *
     * @return the rows, which the table is expected to reorder
     */
    public List<Transfer> getSuggested() {
        return suggested;
    }

    public boolean isMember() {
        return member;
    }

    public boolean isLoaded() {
        return trip != null;
    }

    /**
     * Why the page could not be built, if it could not.
     *
     * <p>Kept apart from the toasts on purpose. A toast reports what
     * happened to an action and then leaves; this is a state the reader is
     * still in, so the page renders it in place with a way out.
     */
    private String loadError;

    /** @return the sentence explaining an unbuildable page, or null */
    public String getLoadError() {
        return loadError;
    }
}
