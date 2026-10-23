package com.tripmate.ejb;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.tripmate.entity.SummaryDeliveryStatus;
import com.tripmate.entity.TripSummaryEmail;
import com.tripmate.entity.TripSummaryKind;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.remote.ExpenseServiceRemote;
import com.tripmate.remote.ItineraryServiceRemote;
import com.tripmate.dto.SettlementDTO;
import com.tripmate.dto.TripSummaryEmailDTO;
import com.tripmate.dto.CategoryTotalDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.dto.TripDTO;
import com.tripmate.dto.TripMemberDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.entity.Trip;
import com.tripmate.entity.TripMember;
import com.tripmate.entity.TripRole;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.User;
import com.tripmate.mail.Email;
import com.tripmate.mail.MailService;
import com.tripmate.mail.Templates;
import com.tripmate.remote.ReportServiceRemote;
import com.tripmate.remote.TripServiceRemote;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;

/**
 * Trips and their membership.
 *
 * <p>Stateless with container-managed transactions, so no method starts or
 * commits a transaction of its own. The one place that matters is
 * {@link #createTrip}: the trip and its admin membership are written inside the
 * same transaction, which is what makes a trip without an administrator
 * impossible rather than merely unlikely.
 */
@Stateless
public class TripService implements TripServiceRemote {

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    /**
     * The aggregates the summary is built from.
     *
     * <p>Injected by its interface rather than by its implementation class. The
     * bean exposes a remote business interface, and a bean that does is not
     * given a no-interface local view, so asking for the class fails at
     * deployment with a JNDI lookup rather than at compile time.
     */
    @EJB
    private ReportServiceRemote reportService;

    /** Injected through its local no-interface view; see MailService. */
    @EJB
    private MailService mailService;

    /**
     * Read for the closing summary, which lists what was planned.
     *
     * <p>Injected rather than queried here directly so the itinerary is
     * assembled by the one place that knows how, and through the remote
     * interface because that is the only view this bean has of it. There is no
     * cycle: ItineraryService reaches authorisation through the static
     * {@code TripAccess} helper and never through this bean.
     */
    @EJB
    private ItineraryServiceRemote itineraryService;

    /**
     * Read for the repayments, so the balances summary can say what a reader
     * still has to settle rather than only their gross position.
     *
     * <p>Injected by its interface for the same reason as the two above.
     */
    @EJB
    private ExpenseServiceRemote expenseService;

    @Override
    public TripDTO createTrip(TripDTO trip, Long creatorId) {
        require(trip, "trip");
        require(creatorId, "creatorId");
        require(trip.getTripName(), "tripName");

        User creator = em.find(User.class, creatorId);
        if (creator == null) {
            throw new ValidationException("no such user: " + creatorId);
        }

        Trip entity = new Trip();
        entity.setTripName(trip.getTripName().trim());
        entity.setDestination(trip.getDestination());
        entity.setStartDate(trip.getStartDate());
        entity.setEndDate(trip.getEndDate());
        entity.setDescription(trip.getDescription());
        entity.setStatus(trip.getStatus() == null ? TripStatus.PLANNING : trip.getStatus());
        entity.setCreatedBy(creator);
        // The zone the plan's times will be read in. Given the server's own
        // when the caller named none, rather than left null: null reads as the
        // server's zone anyway, and a value the reader can see and correct is
        // better than a fallback nobody knows is being applied.
        entity.setTimezone(normaliseZone(trip.getTimezone(), ZoneId.systemDefault().getId()));

        em.persist(entity);

        // Written in the same transaction as the trip, so the two cannot come
        // apart. Without this the trip would exist with nobody able to manage
        // it.
        TripMember admin = new TripMember();
        admin.setTrip(entity);
        admin.setUser(creator);
        admin.setRole(TripRole.ADMIN);
        em.persist(admin);

        em.flush();
        return TripDTO.from(entity, creator.getFullName(), 1);
    }

    @Override
    public TripDTO getTrip(Long tripId) {
        if (tripId == null) {
            return null;
        }
        Trip trip = em.find(Trip.class, tripId);
        return trip == null ? null : toDto(trip);
    }

    @Override
    public List<TripDTO> getTripsForUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // Membership decides, not authorship: an invited user sees the trip
        // exactly as the creator does. createdBy is fetch-joined so resolving
        // the administrator's name costs no extra query per trip.
        List<Trip> trips = em.createQuery(
                        "SELECT DISTINCT t FROM Trip t "
                                + "JOIN FETCH t.createdBy "
                                + "JOIN t.members m "
                                + "WHERE m.user.userId = :userId "
                                + "ORDER BY t.startDate DESC", Trip.class)
                .setParameter("userId", userId)
                .getResultList();
        return toDtos(trips);
    }

    @Override
    public List<TripDTO> getAllTrips() {
        List<Trip> trips = em.createQuery(
                        "SELECT t FROM Trip t LEFT JOIN FETCH t.createdBy "
                                + "ORDER BY t.startDate DESC", Trip.class)
                .getResultList();
        return toDtos(trips);
    }

    @Override
    public TripDTO updateTrip(TripDTO trip, Long actedBy) {
        require(trip, "trip");
        require(trip.getTripId(), "tripId");

        Trip entity = em.find(Trip.class, trip.getTripId());
        if (entity == null) {
            throw new ValidationException("no such trip: " + trip.getTripId());
        }
        TripAccess.requireAdmin(em, trip.getTripId(), actedBy, "edit this trip");

        if (trip.getTripName() != null) {
            entity.setTripName(trip.getTripName().trim());
        }
        entity.setDestination(trip.getDestination());
        entity.setStartDate(trip.getStartDate());
        entity.setEndDate(trip.getEndDate());
        entity.setDescription(trip.getDescription());
        // Deliberately set rather than merged like the name: the trip settings
        // dialog always carries a zone, and a blank one means "back to the
        // server's", which is a choice a reader is allowed to make.
        entity.setTimezone(normaliseZone(trip.getTimezone(), null));
        if (trip.getStatus() != null) {
            entity.setStatus(trip.getStatus());
        }

        em.flush();
        return toDto(entity);
    }

    @Override
    public void deleteTrip(Long tripId, Long actedBy) {
        require(tripId, "tripId");

        Trip entity = em.find(Trip.class, tripId);
        if (entity == null) {
            throw new ValidationException("no such trip: " + tripId);
        }
        TripAccess.requireAdmin(em, tripId, actedBy, "delete this trip");

        // The children are removed by ON DELETE CASCADE in the schema rather
        // than by JPA, because Trip's collections deliberately carry no
        // cascade. Two mechanisms for one job would eventually disagree.
        em.remove(entity);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    @Override
    public List<TripMemberDTO> getTripMembers(Long tripId) {
        if (tripId == null) {
            return List.of();
        }
        return em.createQuery(
                        "SELECT m FROM TripMember m JOIN FETCH m.user "
                                + "WHERE m.trip.tripId = :tripId "
                                + "ORDER BY m.user.fullName", TripMember.class)
                .setParameter("tripId", tripId)
                .getResultList()
                .stream()
                .map(TripMemberDTO::from)
                .toList();
    }

    @Override
    public TripMemberDTO addMember(Long tripId, Long userId, TripRole role, Long actedBy) {
        require(tripId, "tripId");
        require(userId, "userId");

        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }
        TripAccess.requireAdmin(em, tripId, actedBy, "invite people to this trip");

        User user = em.find(User.class, userId);
        if (user == null) {
            throw new ValidationException("no such user: " + userId);
        }

        // Business rule ROADMAP section 8.3. Checked here as well as by the
        // unique constraint, so the caller gets a sentence rather than a
        // constraint violation. The constraint stays the backstop against two
        // concurrent calls both passing this check.
        if (findMembership(tripId, userId) != null) {
            throw new ValidationException(
                    user.getFullName() + " is already a member of this trip");
        }

        TripMember member = new TripMember();
        member.setTrip(trip);
        member.setUser(user);
        member.setRole(role == null ? TripRole.MEMBER : role);
        em.persist(member);
        em.flush();

        // Both entry points land here: addMemberByEmail resolves an address and
        // delegates, so notifying in this one place covers the form the
        // application actually uses and the id-based call behind it.
        //
        // The call is asynchronous and never throws. The container defers it
        // until this transaction commits, so nobody is told they are on a trip
        // that then rolled back, and a mail server that is down cannot fail an
        // invitation that succeeded.
        notifyAddedToTrip(trip, user, actorName(actedBy));

        return TripMemberDTO.from(member);
    }

    /**
     * The display name of the person doing something, for a message.
     *
     * @param userId the acting user's id, which may be null
     * @return the name, or null so the message falls back to a neutral word
     */
    private String actorName(Long userId) {
        if (userId == null) {
            return null;
        }
        User actor = em.find(User.class, userId);
        return actor == null ? null : actor.getFullName();
    }

    /**
     * Tells somebody they have been put on a trip.
     *
     * @param trip   the trip, whose members and dates the message describes
     * @param user   the person added
     * @param actor  the name of whoever added them, which may be null
     */
    private void notifyAddedToTrip(Trip trip, User user, String actor) {
        mailService.send(Templates.addedToTrip(
                user.getEmail(),
                user.getFullName(),
                actor,
                trip.getTripName(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                mailService.getBaseUrl() + "/trips/detail.xhtml?tripId=" + trip.getTripId()));
    }

    @Override
    public void removeMember(Long tripId, Long userId, Long actedBy) {
        require(tripId, "tripId");
        require(userId, "userId");

        TripAccess.requireAdmin(em, tripId, actedBy, "remove members");

        TripMember member = findMembership(tripId, userId);
        if (member == null) {
            throw new ValidationException("user " + userId
                    + " is not a member of trip " + tripId);
        }

        // A trip with no administrator cannot be managed at all: nobody could
        // invite, remove or edit, and there is no way to appoint a replacement
        // through the application. So the last one is not allowed to leave.
        if (member.getRole() == TripRole.ADMIN && TripAccess.countAdmins(em, tripId) <= 1) {
            throw new ValidationException(
                    "this is the trip's only administrator, so removing them would "
                            + "leave nobody able to manage it. Make someone else an "
                            + "administrator first.");
        }

        em.remove(member);
        em.flush();
    }

    @Override
    public boolean isMember(Long tripId, Long userId) {
        if (tripId == null || userId == null) {
            return false;
        }
        return findMembership(tripId, userId) != null;
    }

    @Override
    public TripRole getMemberRole(Long tripId, Long userId) {
        TripMember member = tripId == null || userId == null
                ? null
                : findMembership(tripId, userId);
        return member == null ? null : member.getRole();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TripMember findMembership(Long tripId, Long userId) {
        try {
            return em.createQuery(
                            "SELECT m FROM TripMember m "
                                    + "WHERE m.trip.tripId = :tripId AND m.user.userId = :userId",
                            TripMember.class)
                    .setParameter("tripId", tripId)
                    .setParameter("userId", userId)
                    .getSingleResult();
        } catch (NoResultException none) {
            return null;
        }
    }

    /** Single trip: one extra count query is cheaper than a second round trip. */
    private TripDTO toDto(Trip trip) {
        long members = em.createQuery(
                        "SELECT COUNT(m) FROM TripMember m WHERE m.trip.tripId = :id", Long.class)
                .setParameter("id", trip.getTripId())
                .getSingleResult();
        User admin = trip.getCreatedBy();
        return TripDTO.from(trip,
                admin == null ? null : admin.getFullName(),
                (int) members);
    }

    /**
     * Maps a list of trips without the N+1 pattern.
     *
     * <p>Member counts for every trip come back in one grouped query, so
     * listing twenty trips costs two queries rather than twenty-one. The
     * convenience {@code TripDTO.from(trip)} overload would load every member
     * collection just to call {@code size()} on it.
     */
    private List<TripDTO> toDtos(List<Trip> trips) {
        if (trips.isEmpty()) {
            return List.of();
        }

        List<Long> tripIds = trips.stream().map(Trip::getTripId).toList();

        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "SELECT m.trip.tripId, COUNT(m) FROM TripMember m "
                                + "WHERE m.trip.tripId IN :ids GROUP BY m.trip.tripId",
                        Object[].class)
                .setParameter("ids", tripIds)
                .getResultList()) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        List<TripDTO> result = new ArrayList<>(trips.size());
        for (Trip trip : trips) {
            User admin = trip.getCreatedBy();
            result.add(TripDTO.from(trip,
                    admin == null ? null : admin.getFullName(),
                    counts.getOrDefault(trip.getTripId(), 0)));
        }
        return result;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new ValidationException(name + " is required");
        }
    }

    /**
     * Adds someone to a trip by their email address.
     *
     * <p>Replaces the picker that listed every account on the platform. That
     * picker was populated from {@code getAllUsers()}, so any trip
     * administrator could read the full user directory, which is not something
     * a trip organiser has any business seeing.
     *
     * <p>The address has to match exactly. There is no prefix or partial
     * search, which is the point: a partial search is a directory again, one
     * letter at a time.
     *
     * <h2>The trade-off this makes</h2>
     *
     * A refusal says whether the address has an account, so a trip
     * administrator can tell someone to sign up first rather than leaving them
     * guessing. That is a deliberate, narrow disclosure: one address at a time,
     * to someone who already administers a trip, rather than a list of
     * everybody. The notifications phase replaces it with a pending invitation
     * that gives the same answer without revealing anything, because it does
     * not need to know whether the account exists yet.
     *
     * @return the membership that was created
     * @throws ValidationException when the address is unknown, already on the
     *         trip, or not a well-formed address
     */
    @Override
    public TripMemberDTO addMemberByEmail(Long tripId, String email, TripRole role, Long actedBy) {
        require(tripId, "tripId");
        require(email, "email");

        String address = email.trim();

        // Checked here rather than only in the browser, because a form is not a
        // validation layer. Deliberately permissive: an address that looks
        // broadly right is accepted, since the only real proof is delivery.
        if (!address.contains("@") || address.startsWith("@") || address.endsWith("@")
                || address.indexOf(' ') >= 0) {
            throw new ValidationException("\"" + address + "\" is not an email address.");
        }

        User user = findByEmail(address);
        if (user == null) {
            throw new ValidationException("No TripMate account uses " + address
                    + ". Ask them to create one, and then invite them again.");
        }

        return addMember(tripId, user.getUserId(), role, actedBy);
    }

    /**
     * Changes what a member is allowed to do.
     *
     * <p>Until this existed, a trip's administrator could not be joined by
     * another: {@code removeMember} refuses to remove the last one and tells
     * the caller to promote someone first, and there was no way to promote
     * anyone. The advice was impossible to follow.
     *
     * <p>Demoting the last administrator is refused for the same reason
     * removing them is: a trip with nobody able to manage it cannot be
     * repaired from inside the application.
     */
    @Override
    public TripMemberDTO updateMemberRole(Long tripId, Long userId, TripRole role, Long actedBy) {
        require(tripId, "tripId");
        require(userId, "userId");

        TripAccess.requireAdmin(em, tripId, actedBy, "change what a member can do");

        TripMember member = findMembership(tripId, userId);
        if (member == null) {
            throw new ValidationException("user " + userId + " is not a member of trip " + tripId);
        }
        TripRole wanted = role == null ? TripRole.MEMBER : role;

        if (member.getRole() == TripRole.ADMIN && wanted != TripRole.ADMIN
                && TripAccess.countAdmins(em, tripId) <= 1) {
            throw new ValidationException(
                    "this is the trip's only administrator, so they cannot step down "
                            + "until someone else is made one. Promote someone first.");
        }

        member.setRole(wanted);
        em.flush();
        return TripMemberDTO.from(member);
    }

    /**
     * Removes the caller from a trip of their own accord.
     *
     * <p>{@code removeMember} needs an administrator, so an ordinary member had
     * no way to leave. The last-administrator rule still applies: an
     * administrator cannot walk out and leave the trip unmanageable.
     */
    @Override
    public void leaveTrip(Long tripId, Long userId) {
        require(tripId, "tripId");
        if (userId == null) {
            throw new ValidationException("no user was named, so nobody can leave");
        }

        TripMember member = findMembership(tripId, userId);
        if (member == null) {
            throw new ValidationException("you are not a member of this trip");
        }
        if (member.getRole() == TripRole.ADMIN && TripAccess.countAdmins(em, tripId) <= 1) {
            throw new ValidationException(
                    "you are the trip's only administrator, so leaving would leave nobody "
                            + "able to manage it. Make someone else an administrator first.");
        }

        Trip trip = member.getTrip();
        trip.removeMember(member);
        em.remove(member);
        em.flush();
    }

    /**
     * Emails part of the trip summary to some or all of its members.
     *
     * <p>One message per person rather than one with everybody on it. The
     * figures are the same, but each reader's own position is different and is
     * the part they actually opened it for, so it is written into each copy
     * rather than left to be found in a table.
     *
     * <p>Any member may send it, not only an administrator. What it contains is
     * already readable by every member, and the recipient list is the sender's
     * to choose, so the worst an ordinary member can do is mail their
     * colleagues something they could already see.
     *
     * <h2>The sends are not asynchronous</h2>
     *
     * Every other message in the application is handed to {@code MailService}
     * and forgotten, because it is a notification attached to an action that has
     * already succeeded. This one is different: it writes a permanent record of
     * what went out, and a record that cannot tell a delivered message from one
     * refused by a mail server that was down is a record that misleads the
     * person reading it. So the sends are made through the variant that answers,
     * and the answers are what is written down.
     *
     * <p>The cost is that the caller waits on SMTP, once per recipient. For a
     * group trip that is a handful of messages, and the wait buys the only
     * honest version of the log.
     */
    @Override
    public TripSummaryEmailDTO emailTripSummary(Long tripId, Long actedBy,
            TripSummaryKind kind, List<Long> recipientIds) {
        require(tripId, "tripId");
        TripAccess.requireMember(em, tripId, actedBy, "send the trip summary");
        if (kind == null) {
            throw new ValidationException("say whether the itinerary or the balances are being sent");
        }

        Trip trip = em.find(Trip.class, tripId);
        if (trip == null) {
            throw new ValidationException("no such trip: " + tripId);
        }

        List<ItineraryItemDTO> itinerary = itineraryService.getItineraryForTrip(tripId);
        List<CategoryTotalDTO> categories =
                reportService.getCategoryTotalsForTrip(tripId, actedBy);
        List<MemberBalanceDTO> balances = reportService.getMemberBalances(tripId, actedBy);

        // Refused rather than sent empty. A message whose whole content is "no
        // expenses have been recorded" is not a summary, and the reader who
        // opens it learns only that somebody pressed a button that should have
        // been unavailable.
        if (kind == TripSummaryKind.ITINERARY && itinerary.isEmpty()) {
            throw new ValidationException(
                    "there is nothing planned on this trip yet, so there is no itinerary to send");
        }
        if (kind == TripSummaryKind.BALANCES && balances.isEmpty()) {
            throw new ValidationException(
                    "nobody is on this trip yet, so there are no balances to send");
        }
        if (kind == TripSummaryKind.BALANCES && categories.isEmpty()) {
            throw new ValidationException(
                    "nothing has been spent on this trip yet, so there are no balances to send");
        }

        BigDecimal total = categories.stream()
                .map(CategoryTotalDTO::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int expenseCount = categories.stream()
                .mapToInt(c -> c.getExpenseCount() == null ? 0 : c.getExpenseCount().intValue())
                .sum();
        String url = mailService.getBaseUrl() + "/trips/detail.xhtml?tripId=" + tripId;

        // What each member still has to settle, once the repayments already
        // recorded are counted. This is the figure the reader can act on; the
        // gross difference is the fallback when the trip has no repayments.
        Map<Long, BigDecimal> outstanding = outstandingByMember(tripId, balances);

        List<TripMemberDTO> chosen = chooseRecipients(getTripMembers(tripId), recipientIds);
        if (chosen.isEmpty()) {
            throw new ValidationException("nobody was chosen to receive it");
        }

        int sent = 0;
        int failed = 0;
        boolean anyAttempted = false;
        boolean anyTransport = false;
        // Who it was accepted for, by name, so the log can answer "who did this
        // reach" and not only how many.
        StringBuilder reached = new StringBuilder();

        for (TripMemberDTO member : chosen) {
            User user = em.find(User.class, member.getUserId());
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                // Counted as a failure rather than skipped silently: the sender
                // chose this person, and a log that says everybody was written
                // to when one of them has no address is the same lie as one
                // that ignores a refused message.
                failed++;
                continue;
            }

            Email message = kind == TripSummaryKind.ITINERARY
                    ? Templates.itinerarySummary(
                            user.getEmail(), member.getFullName(),
                            trip.getTripName(), trip.getStartDate(), trip.getEndDate(),
                            trip.getStatus(), itinerary, url)
                    : Templates.balancesSummary(
                            user.getEmail(), member.getFullName(), member.getUserId(),
                            trip.getTripName(), trip.getStartDate(), trip.getEndDate(),
                            trip.getStatus(), total, expenseCount,
                            categories, balances, outstanding.get(member.getUserId()), url);

            MailService.Delivery delivery = mailService.deliver(message);
            anyAttempted = true;
            anyTransport |= delivery != MailService.Delivery.NO_TRANSPORT;
            if (delivery == MailService.Delivery.SENT) {
                sent++;
                if (reached.length() > 0) {
                    reached.append(", ");
                }
                reached.append(member.getFullName());
            } else {
                failed++;
            }
        }

        SummaryDeliveryStatus status;
        if (!anyTransport && anyAttempted) {
            status = SummaryDeliveryStatus.NOT_CONFIGURED;
        } else if (failed == 0) {
            status = SummaryDeliveryStatus.SENT;
        } else if (sent == 0) {
            status = SummaryDeliveryStatus.FAILED;
        } else {
            status = SummaryDeliveryStatus.PARTIAL;
        }

        User actor = em.find(User.class, actedBy);
        TripSummaryEmail entry = new TripSummaryEmail();
        entry.setTrip(trip);
        entry.setSentBy(actor);
        entry.setSentByName(actor == null ? "A member" : actor.getFullName());
        entry.setKind(kind);
        entry.setRecipientCount(sent);
        entry.setRecipientNames(truncate(reached.toString(), 1000));
        entry.setFailedCount(failed);
        entry.setStatus(status);
        em.persist(entry);
        em.flush();

        return TripSummaryEmailDTO.from(entry);
    }

    /**
     * A string clipped to a column's width, at a comma where there is one.
     *
     * <p>The recipient names are denormalised into a fixed-width column, and a
     * trip with enough people on it can exceed it. Clipping at a comma keeps
     * every name in the list whole; clipping mid-name would put half a name in
     * the log, which reads as a different person.
     */
    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        int cut = value.lastIndexOf(',', limit);
        return cut > 0 ? value.substring(0, cut) : value.substring(0, limit);
    }

    /**
     * The people a send is addressed to.
     *
     * <p>An empty or null list means everybody, which is what the form sends
     * when "everyone" is chosen. Ids are intersected with the membership rather
     * than trusted, so a hand-built request cannot mail somebody who is not on
     * the trip.
     */
    private List<TripMemberDTO> chooseRecipients(List<TripMemberDTO> members, List<Long> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return members;
        }
        List<TripMemberDTO> result = new ArrayList<>();
        for (TripMemberDTO member : members) {
            if (chosen.contains(member.getUserId())) {
                result.add(member);
            }
        }
        return result;
    }

    /**
     * What each member still has to settle, by user id.
     *
     * <p>The same arithmetic the balances page does: net position, plus what
     * they have paid out in recorded repayments, minus what they have received.
     * Positive means they are still owed it.
     */
    private Map<Long, BigDecimal> outstandingByMember(Long tripId, List<MemberBalanceDTO> balances) {
        Map<Long, BigDecimal> repaidOut = new HashMap<>();
        Map<Long, BigDecimal> received = new HashMap<>();
        for (SettlementDTO settlement : expenseService.getSettlementsForTrip(tripId)) {
            repaidOut.merge(settlement.getPayerId(), settlement.getAmount(), BigDecimal::add);
            received.merge(settlement.getReceiverId(), settlement.getAmount(), BigDecimal::add);
        }

        Map<Long, BigDecimal> outstanding = new HashMap<>();
        for (MemberBalanceDTO balance : balances) {
            BigDecimal net = balance.getTotalPaid().subtract(balance.getTotalOwed());
            outstanding.put(balance.getUserId(), net
                    .add(repaidOut.getOrDefault(balance.getUserId(), BigDecimal.ZERO))
                    .subtract(received.getOrDefault(balance.getUserId(), BigDecimal.ZERO)));
        }
        return outstanding;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ordered by the database rather than in the DTO, because the list is
     * read newest first and there is an index on exactly that.
     */
    @Override
    public List<TripSummaryEmailDTO> getSummaryEmails(Long tripId, Long callerId) {
        require(tripId, "tripId");
        TripAccess.requireMember(em, tripId, callerId, "see when the summary was sent");

        return em.createQuery(
                        "SELECT new com.tripmate.dto.TripSummaryEmailDTO("
                                + "e.summaryId, e.sentByName, e.kind, e.recipientCount, "
                                + "e.recipientNames, e.failedCount, e.status, e.sentAt) "
                                + "FROM TripSummaryEmail e "
                                + "WHERE e.trip.tripId = :tripId "
                                + "ORDER BY e.sentAt DESC, e.summaryId DESC",
                        TripSummaryEmailDTO.class)
                .setParameter("tripId", tripId)
                .getResultList();
    }

    /** Looks a user up by exact address, case-insensitively. */
    private User findByEmail(String email) {
        try {
            return em.createQuery(
                            "SELECT u FROM User u WHERE LOWER(u.email) = :email", User.class)
                    .setParameter("email", email.toLowerCase(java.util.Locale.ROOT))
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException none) {
            return null;
        }
    }

    /**
     * A zone id that this runtime recognises, or the fallback.
     *
     * <p>Accepted and stored as an IANA id so the value means the same thing to
     * every reader of it. A caller that sends something this runtime cannot
     * resolve gets the fallback rather than an error, because the zone is
     * metadata about the plan and refusing to create a trip over it would be
     * the wrong trade; the dialog offers a list, so a bad value means a
     * hand-written request.
     *
     * @param requested what was asked for, possibly null or blank
     * @param fallback  what to use instead, possibly null to store nothing
     * @return a resolved zone id, or the fallback
     */
    private static String normaliseZone(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        String candidate = requested.trim();
        try {
            return ZoneId.of(candidate).getId();
        } catch (DateTimeException unknownZone) {
            return fallback;
        }
    }
}
