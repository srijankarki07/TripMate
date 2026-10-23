package com.tripmate.remote;

import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.exception.ValidationException;

import jakarta.ejb.Remote;

/**
 * Expenses, their splits, and the settlements that clear them.
 *
 * <p>An expense and its splits are written together. A split has no meaning
 * apart from the expense it belongs to, so there is no method here that creates
 * one on its own.
 */
@Remote
public interface ExpenseServiceRemote {

    /**
     * Writes an expense and its splits in one transaction.
     *
     * <p>This is the write path. In Phase 7 the message-driven bean calls it
     * after reading an expense off the queue, which is why it is separate from
     * {@link #submitExpenseAsync}.
     *
     * @param expense the expense; {@code expenseId} is ignored. The splits come
     *                from {@link ExpenseDTO#getSplits()}, and each identifies
     *                its participant by user id.
     * @param actedBy who is recording it; must be a member of the trip
     * @return the stored expense with generated ids
     * @throws ValidationException if the trip or payer does not exist, a
     *                             required field is missing, the amount is not
     *                             positive, a participant is unknown, the splits
     *                             do not sum to the amount, or the caller is not
     *                             a member of the trip
     */
    ExpenseDTO persistExpense(ExpenseDTO expense, Long actedBy);

    /**
     * Records an expense submitted by a user, and returns once it is stored.
     *
     * <p>The asynchronous route through {@link #submitExpenseAsync} is what the
     * expense form uses. This one stays for callers that need the generated id
     * straight away.
     *
     * @param actedBy who is recording it; must be a member of the trip
     */
    ExpenseDTO submitExpense(ExpenseDTO expense, Long actedBy);

    /**
     * Submits an expense without waiting for it to be written.
     *
     * <p>Puts an {@code ExpenseMessage} on the expense queue and returns. The
     * message-driven bean performs the write, which is what the ROADMAP
     * section 10.1 rationale describes: several rows are involved and the
     * browser should not be blocked on them.
     *
     * <p>The input is validated, and the caller's membership checked, before
     * the message is sent. An expense that could never be stored is refused
     * while the user is still looking at the form rather than being rejected
     * later by a consumer that has no way to tell anyone.
     *
     * <p>There is no single-argument form. The acting user is not optional, and
     * an overload that guessed it from the expense's payer would let a caller
     * record an expense against someone else's membership.
     *
     * @param actedBy who is submitting it; must be a member of the trip
     */
    void submitExpenseAsync(ExpenseDTO expense, Long actedBy);

    /** @return the expense with its splits, or {@code null} if no such id */
    ExpenseDTO getExpense(Long expenseId);

    /**
     * Query 2 (ROADMAP section 9.2): a trip's expenses, most recent first.
     *
     * <p>Navigates Expense to Trip. Newest first because the expense list is
     * read as a running log of the trip.
     *
     * @return the expenses with their splits; empty if the trip has none
     */
    List<ExpenseDTO> getExpensesForTrip(Long tripId);

    /**
     * The newest expenses across every trip a user belongs to, newest first.
     *
     * <p>For the dashboard, which wants a short list spanning all of a reader's
     * trips rather than one trip's expenses. Calling {@link #getExpensesForTrip}
     * once per trip and merging the results would read every expense of every
     * trip to display a handful, and the cost would grow with the reader's
     * history rather than with the size of the list being shown.
     *
     * <p>Authorization is inherent rather than checked: the query is scoped to
     * trips the user is a member of, so it cannot return an expense from a trip
     * they cannot see, and there is no trip to verify membership against.
     *
     * @param userId the reader
     * @param limit  how many to return at most, newest first
     * @return the expenses, empty when the user is unknown or belongs to nothing
     */
    List<ExpenseDTO> getRecentExpensesForUser(Long userId, int limit);

    /**
     * Updates an expense.
     *
     * <p>Splits are replaced wholesale when the DTO carries any, and left alone
     * when it carries none, so a caller editing only the description does not
     * have to resend the splits to avoid deleting them.
     *
     * @param actedBy who is editing it; must be a member of the trip
     * @throws ValidationException if the expense does not exist, the replacement
     *                             splits are invalid, or the caller is not a
     *                             member of the trip
     */
    ExpenseDTO updateExpense(ExpenseDTO expense, Long actedBy);

    /**
     * Deletes an expense and its splits.
     *
     * @param actedBy who is deleting it; must be a member of the trip
     * @throws ValidationException if the expense does not exist, or the caller
     *                             is not a member of the trip
     */
    void deleteExpense(Long expenseId, Long actedBy);

    // ------------------------------------------------------------------
    // Settlements
    // ------------------------------------------------------------------

    /**
     * Records a repayment between two members of a trip.
     *
     * @param actedBy who is recording it; must be a member of the trip
     * @throws ValidationException if the trip, payer or receiver does not exist,
     *                             the two parties are the same person, the
     *                             amount is not positive, or the caller is not a
     *                             member of the trip
     */
    SettlementDTO recordSettlement(SettlementDTO settlement, Long actedBy);

    /** @return the settlement, or {@code null} if no such id */
    SettlementDTO getSettlement(Long settlementId);

    /** @return the trip's settlements, most recent first; empty if none */
    List<SettlementDTO> getSettlementsForTrip(Long tripId);

    /**
     * Deletes a settlement.
     *
     * @param actedBy who is deleting it; must be a member of the trip
     * @throws ValidationException if the settlement does not exist, or the
     *                             caller is not a member of the trip
     */
    void deleteSettlement(Long settlementId, Long actedBy);

    // ------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------

    /** @return the sum of the trip's expenses, zero if it has none */
    BigDecimal getTotalSpentForTrip(Long tripId);
}
