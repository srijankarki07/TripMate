package com.tripmate.ejb;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.SplitDTO;
import com.tripmate.messaging.ExpenseMessage;
import com.tripmate.exception.ValidationException;
import com.tripmate.entity.Expense;
import com.tripmate.entity.ExpenseSplit;
import com.tripmate.entity.Settlement;
import com.tripmate.entity.Trip;
import com.tripmate.entity.User;
import com.tripmate.remote.ExpenseServiceRemote;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.jms.JMSConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Queue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Expenses, their splits, and the settlements that clear them.
 *
 * <h2>Business rules landed early, and why</h2>
 *
 * ROADMAP section 14 puts the section 8.3 business rules in Phase 9. Two of
 * them are enforced here already, at the tier that owns writes:
 *
 * <ul>
 *   <li>an expense amount must be greater than zero, and</li>
 *   <li>the splits of an expense must sum to its amount.</li>
 * </ul>
 *
 * The second is the central invariant of the whole data model. Phase 5 verifies
 * the balance queries against the seeded data, and if the write path could
 * produce an expense whose splits do not add up, that verification would be
 * standing on data the application is free to corrupt. A rule that a later
 * phase depends on belongs in the earlier phase.
 *
 * <p>What remains for Phase 9 is everything needing the caller's identity,
 * which these method signatures do not carry: that only an admin may remove a
 * member, and that a user may only add expenses to trips they belong to.
 */
@Stateless
public class ExpenseService implements ExpenseServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * The JMS context is injected rather than created from a connection
     * factory. Injected this way it participates in the bean's transaction, so
     * the send is committed with the caller's transaction and a rollback takes
     * the message with it. A context created by hand would be separate and
     * would also have to be closed.
     */
    @Inject
    @JMSConnectionFactory("jms/ExpenseQueueFactory")
    private JMSContext jmsContext;

    @Resource(lookup = "jms/ExpenseQueue")
    private Queue expenseQueue;

    @Override
    public ExpenseDTO persistExpense(ExpenseDTO expense) {
        return write(expense);
    }

    @Override
    public ExpenseDTO submitExpense(ExpenseDTO expense) {
        return write(expense);
    }

    @Override
    public void submitExpenseAsync(ExpenseDTO expense) {
        submitExpenseAsync(expense, expense == null ? null : expense.getPaidById());
    }

    /**
     * Submits an expense for asynchronous writing, recording who asked for it.
     *
     * <p>The caller's identity is a separate argument rather than being read
     * from the expense, because the payer and the person submitting the form
     * are not necessarily the same, and a stateless bean has no way to tell.
     */
    @Override
    public void submitExpenseAsync(ExpenseDTO expense, Long submittedBy) {
        require(expense, "expense");

        // Checked here rather than left to the consumer. The point of the
        // asynchronous path is to move the write, not to move the validation:
        // rejecting obviously bad input in the caller's own transaction is
        // cheaper and gives the user an immediate answer.
        validate(expense);

        jmsContext.createProducer().send(expenseQueue, new ExpenseMessage(expense, submittedBy));
    }

    @Override
    public ExpenseDTO getExpense(Long expenseId) {
        if (expenseId == null) {
            return null;
        }
        return ExpenseDTO.from(em.find(Expense.class, expenseId));
    }

    @Override
    public List<ExpenseDTO> getExpensesForTrip(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        // QUERY 2 (ROADMAP section 9.2). Navigates Expense to Trip.
        //
        // DISTINCT is required, not cosmetic: fetch-joining the splits
        // collection yields one row per split, so an expense with four splits
        // would come back four times.
        return em.createQuery(
                        "SELECT DISTINCT e FROM Expense e "
                                + "LEFT JOIN FETCH e.splits "
                                + "LEFT JOIN FETCH e.paidBy "
                                + "WHERE e.trip.tripId = :tripId "
                                + "ORDER BY e.expenseDate DESC, e.expenseId DESC",
                        Expense.class)
                .setParameter("tripId", tripId)
                .getResultList()
                .stream()
                .map(ExpenseDTO::from)
                .toList();
    }

    @Override
    public ExpenseDTO updateExpense(ExpenseDTO expense) {
        require(expense, "expense");
        require(expense.getExpenseId(), "expenseId");

        Expense entity = em.find(Expense.class, expense.getExpenseId());
        if (entity == null) {
            throw new ValidationException("no such expense: " + expense.getExpenseId());
        }

        if (expense.getDescription() != null) {
            entity.setDescription(expense.getDescription().trim());
        }
        if (expense.getAmount() != null) {
            requirePositive(expense.getAmount(), "amount");
            entity.setAmount(expense.getAmount());
        }
        entity.setCategory(expense.getCategory());
        if (expense.getExpenseDate() != null) {
            entity.setExpenseDate(expense.getExpenseDate());
        }
        if (expense.getPaidById() != null) {
            entity.setPaidBy(requireUser(expense.getPaidById()));
        }

        // Only replaced when the caller actually sent some. An edit that
        // changes the description should not silently delete every split.
        if (!expense.getSplits().isEmpty()) {
            replaceSplits(entity, expense.getSplits());

            // Re-checked after the replacement, since the new splits are what
            // will be stored.
            BigDecimal total = entity.getSplitTotal();
            if (entity.getAmount().compareTo(total) != 0) {
                throw new ValidationException(
                        "splits total " + total + " does not equal the amount "
                                + entity.getAmount());
            }
        }

        em.flush();
        return ExpenseDTO.from(entity);
    }

    @Override
    public void deleteExpense(Long expenseId) {
        require(expenseId, "expenseId");

        Expense entity = em.find(Expense.class, expenseId);
        if (entity == null) {
            throw new ValidationException("no such expense: " + expenseId);
        }
        // The splits go with it through cascade and orphan removal on
        // Expense.splits, so there is no need to delete them by hand.
        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Settlements
    // ------------------------------------------------------------------

    @Override
    public SettlementDTO recordSettlement(SettlementDTO settlement) {
        require(settlement, "settlement");
        require(settlement.getTripId(), "tripId");
        require(settlement.getPayerId(), "payerId");
        require(settlement.getReceiverId(), "receiverId");
        require(settlement.getAmount(), "amount");
        requirePositive(settlement.getAmount(), "amount");

        // Business rule ROADMAP section 8.3, and also a CHECK constraint.
        // Checked here so the caller gets a sentence.
        if (settlement.getPayerId().equals(settlement.getReceiverId())) {
            throw new ValidationException(
                    "a settlement must be between two different people");
        }

        Trip trip = requireTrip(settlement.getTripId());
        User payer = requireUser(settlement.getPayerId());
        User receiver = requireUser(settlement.getReceiverId());

        Settlement entity = new Settlement();
        entity.setTrip(trip);
        entity.setPayer(payer);
        entity.setReceiver(receiver);
        entity.setAmount(settlement.getAmount());
        entity.setNote(settlement.getNote());
        if (settlement.getSettledAt() != null) {
            entity.setSettledAt(settlement.getSettledAt());
        }

        em.persist(entity);
        em.flush();
        return SettlementDTO.from(entity);
    }

    @Override
    public SettlementDTO getSettlement(Long settlementId) {
        if (settlementId == null) {
            return null;
        }
        return SettlementDTO.from(em.find(Settlement.class, settlementId));
    }

    @Override
    public List<SettlementDTO> getSettlementsForTrip(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT s FROM Settlement s "
                                + "JOIN FETCH s.payer "
                                + "JOIN FETCH s.receiver "
                                + "WHERE s.trip.tripId = :tripId "
                                + "ORDER BY s.settledAt DESC, s.settlementId DESC",
                        Settlement.class)
                .setParameter("tripId", tripId)
                .getResultList()
                .stream()
                .map(SettlementDTO::from)
                .toList();
    }

    @Override
    public void deleteSettlement(Long settlementId) {
        require(settlementId, "settlementId");

        Settlement entity = em.find(Settlement.class, settlementId);
        if (entity == null) {
            throw new ValidationException("no such settlement: " + settlementId);
        }
        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------

    @Override
    public BigDecimal getTotalSpentForTrip(Long tripId) {
        if (tripId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = em.createQuery(
                        "SELECT COALESCE(SUM(e.amount), 0) FROM Expense e "
                                + "WHERE e.trip.tripId = :tripId", BigDecimal.class)
                .setParameter("tripId", tripId)
                .getSingleResult();
        return total == null ? BigDecimal.ZERO : total;
    }

    // ------------------------------------------------------------------
    // The write path
    // ------------------------------------------------------------------

    /**
     * Checks everything that has to hold before an expense can be written.
     *
     * <p>Separate from the write so the asynchronous path can apply exactly the
     * same rules in the caller's own transaction. A message that could never be
     * stored should be refused while the user is still looking at the form,
     * rather than put on a queue to be rejected later by a consumer with no way
     * to tell anyone.
     */
    private void validate(ExpenseDTO dto) {
        require(dto, "expense");
        require(dto.getTripId(), "tripId");
        require(dto.getDescription(), "description");
        require(dto.getExpenseDate(), "expenseDate");
        require(dto.getAmount(), "amount");
        requirePositive(dto.getAmount(), "amount");

        if (dto.getSplits().isEmpty()) {
            throw new ValidationException("an expense needs at least one split");
        }
        if (!dto.isBalanced()) {
            throw new ValidationException(
                    "splits total " + dto.getSplitTotal() + " does not equal the amount "
                            + dto.getAmount());
        }
    }

    /**
     * Builds and stores an expense with its splits, in one transaction.
     *
     * <p>Shared by the three submit and persist entry points so they cannot
     * drift apart. Phase 7 will leave only the message-driven bean calling it.
     */
    private ExpenseDTO write(ExpenseDTO dto) {
        validate(dto);

        Trip trip = requireTrip(dto.getTripId());

        Expense entity = new Expense();
        entity.setTrip(trip);
        entity.setDescription(dto.getDescription().trim());
        entity.setAmount(dto.getAmount());
        entity.setCategory(dto.getCategory());
        entity.setExpenseDate(dto.getExpenseDate());

        if (dto.getPaidById() != null) {
            entity.setPaidBy(requireUser(dto.getPaidById()));
        }

        replaceSplits(entity, dto.getSplits());

        em.persist(entity);
        em.flush();
        return ExpenseDTO.from(entity);
    }

    /**
     * Replaces an expense's splits, letting orphan removal delete the old rows.
     *
     * <p>Clearing first rather than diffing keeps this simple, and it is
     * correct because a split carries no identity a caller refers to: the DTO
     * has no split id, so no caller can be holding a reference to one that this
     * would invalidate.
     */
    private void replaceSplits(Expense expense, List<SplitDTO> splits) {
        expense.getSplits().clear();

        List<String> problems = new ArrayList<>();
        for (SplitDTO split : splits) {
            if (split.getUserId() == null) {
                problems.add("a split has no user id");
                continue;
            }
            if (split.getAmountOwed() == null) {
                problems.add("a split for user " + split.getUserId() + " has no amount");
                continue;
            }
            if (split.getAmountOwed().signum() < 0) {
                problems.add("a split for user " + split.getUserId() + " is negative");
                continue;
            }
            User user = em.find(User.class, split.getUserId());
            if (user == null) {
                problems.add("no such user: " + split.getUserId());
                continue;
            }

            ExpenseSplit entity = new ExpenseSplit();
            entity.setUser(user);
            entity.setAmountOwed(split.getAmountOwed());
            entity.setSettled(split.isSettled());
            expense.addSplit(entity);
        }

        if (!problems.isEmpty()) {
            throw new ValidationException(String.join("; ", problems));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Trip requireTrip(Long tripId) {
        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }
        return trip;
    }

    private User requireUser(Long userId) {
        User user = em.find(User.class, userId);
        if (user == null) {
            throw new ValidationException("no such user: " + userId);
        }
        return user;
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value.signum() <= 0) {
            throw new ValidationException(name + " must be greater than zero");
        }
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new ValidationException(name + " is required");
        }
    }
}
