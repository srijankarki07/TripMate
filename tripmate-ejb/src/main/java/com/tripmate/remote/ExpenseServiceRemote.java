package com.tripmate.remote;

import java.math.BigDecimal;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.SettlementDTO;

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
     * @return the stored expense with generated ids
     * @throws IllegalArgumentException if the trip or payer does not exist, a
     *                                  required field is missing, the amount is
     *                                  not positive, a participant is unknown,
     *                                  or the splits do not sum to the amount
     */
    ExpenseDTO persistExpense(ExpenseDTO expense);

    /**
     * Records an expense submitted by a user.
     *
     * <p>Currently delegates to {@link #persistExpense}. Phase 7 changes
     * {@link #submitExpenseAsync} to enqueue a message so the browser is not
     * held while several rows are written; this synchronous entry point stays
     * for callers that need the id straight away.
     */
    ExpenseDTO submitExpense(ExpenseDTO expense);

    /**
     * Submits an expense without waiting for it to be written.
     *
     * <p>Puts an {@code ExpenseMessage} on the expense queue and returns. The
     * message-driven bean performs the write, which is what the ROADMAP
     * section 10.1 rationale describes: several rows are involved and the
     * browser should not be blocked on them.
     *
     * <p>The input is validated before the message is sent, so an expense that
     * could never be stored is refused while the user is still looking at the
     * form rather than being rejected later by a consumer that has no way to
     * tell anyone.
     */
    void submitExpenseAsync(ExpenseDTO expense);

    /**
     * Submits an expense without waiting for it to be written, recording who
     * asked for it.
     *
     * <p>The submitter is a separate argument rather than being read from the
     * expense, because the person filling in the form and the person who paid
     * are not necessarily the same, and a stateless bean cannot tell.
     */
    void submitExpenseAsync(ExpenseDTO expense, Long submittedBy);

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
     * Updates an expense.
     *
     * <p>Splits are replaced wholesale when the DTO carries any, and left alone
     * when it carries none, so a caller editing only the description does not
     * have to resend the splits to avoid deleting them.
     *
     * @throws IllegalArgumentException if the expense does not exist, or the
     *                                  replacement splits are invalid
     */
    ExpenseDTO updateExpense(ExpenseDTO expense);

    /**
     * Deletes an expense and its splits.
     *
     * @throws IllegalArgumentException if the expense does not exist
     */
    void deleteExpense(Long expenseId);

    // ------------------------------------------------------------------
    // Settlements
    // ------------------------------------------------------------------

    /**
     * Records a repayment between two members of a trip.
     *
     * @throws IllegalArgumentException if the trip, payer or receiver does not
     *                                  exist, the two parties are the same
     *                                  person, or the amount is not positive
     */
    SettlementDTO recordSettlement(SettlementDTO settlement);

    /** @return the settlement, or {@code null} if no such id */
    SettlementDTO getSettlement(Long settlementId);

    /** @return the trip's settlements, most recent first; empty if none */
    List<SettlementDTO> getSettlementsForTrip(Long tripId);

    /**
     * Deletes a settlement.
     *
     * @throws IllegalArgumentException if the settlement does not exist
     */
    void deleteSettlement(Long settlementId);

    // ------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------

    /** @return the sum of the trip's expenses, zero if it has none */
    BigDecimal getTotalSpentForTrip(Long tripId);
}
