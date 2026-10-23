package com.tripmate.ejb;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;

import com.tripmate.entity.EmailVerification;
import com.tripmate.entity.User;
import com.tripmate.exception.ValidationException;
import com.tripmate.mail.MailService;
import com.tripmate.mail.Templates;
import com.tripmate.security.PasswordHasher;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Confirming that a new account's email address belongs to the person who
 * typed it.
 *
 * <h2>The rules this is built around</h2>
 *
 * <ol>
 *   <li><b>A code, not a link.</b> Six digits, readable off one screen and
 *       typeable into another. The reset flow sends a link because there is
 *       nothing to type; here the point is that the person is looking at the
 *       application while holding the code.</li>
 *   <li><b>The code is short, so the guessing has to be bounded elsewhere.</b>
 *       A million possibilities is nothing to a script, so the code is stored
 *       under the same slow hash as a password, and a code dies after
 *       {@value #MAX_ATTEMPTS} wrong guesses. The attempt limit is the load-
 *       bearing half: it turns a million possibilities into five.</li>
 *   <li><b>The code is stored hashed.</b> Never in the clear, so a copy of the
 *       table is not a set of keys to every account that is mid-registration.
 *       See {@link EmailVerification} for why this is PBKDF2 where a password
 *       reset token is SHA-256.</li>
 *   <li><b>An expired code is never a dead end.</b> The account survives, the
 *       form survives, and a new code is one press away. This is the part that
 *       is easy to get wrong: a flow that expires into an error state leaves
 *       somebody who did nothing wrong holding an account they cannot finish
 *       and cannot delete. So expiry is reported as expiry, and asking again is
 *       never blocked by the cooldown, only by the hourly cap.</li>
 *   <li><b>Requesting a code is not a way to send somebody mail repeatedly.</b>
 *       One code at a time reaches an inbox, at most
 *       {@value #MAX_CODES_PER_HOUR} an hour, and a replacement spends the
 *       earlier one, so an old message lying in an inbox stops working.</li>
 *   <li><b>Confirming is what makes an account usable.</b> Until then the
 *       account exists, the password works, and signing in leads to this page
 *       rather than to the application. See {@code LoginBean}.</li>
 * </ol>
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>Changing an email address after registration does not re-open
 * verification. That belongs to a change-of-address flow, which needs to keep
 * the old address working until the new one answers, and this project has no
 * such flow. The gap is recorded in {@code docs/phase-18-email-verification.md}
 * rather than left to be discovered.
 */
@Stateless
public class EmailVerificationService {

    private static final Logger LOG = Logger.getLogger(EmailVerificationService.class.getName());

    /** Six digits: 10^6 values. Short enough to retype, bounded by the cap. */
    private static final int CODE_DIGITS = 6;

    private static final int CODE_SPACE = 1_000_000;

    /**
     * How long a code stays good for.
     *
     * <p>Shorter than the thirty minutes a reset link gets, and deliberately:
     * the person is sitting on the page waiting for it, so a long window buys
     * nothing, while every extra minute is another minute a code read over
     * somebody's shoulder is worth something.
     */
    private static final int VALID_MINUTES = 15;

    /**
     * Wrong guesses one code accepts before it is spent.
     *
     * <p>Public because it is told to the person: the email says how many tries
     * the code allows and the verification page counts them down, so the number
     * is part of what the feature promises rather than an internal detail. It
     * is also the half of the design that makes six digits safe, so it is worth
     * being able to point at.
     */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * How long before a live code may be replaced.
     *
     * <p>Does not apply when there is no live code. Somebody whose code has
     * expired, or who has used up its attempts, can ask again immediately:
     * the wait exists to stop a live code being replaced in a loop, not to
     * punish a code that has already stopped working.
     */
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    /** Codes one account may be sent in {@link #WINDOW_MINUTES}. */
    private static final int MAX_CODES_PER_HOUR = 5;

    private static final int WINDOW_MINUTES = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    @PersistenceContext(unitName = "tripmatePU")
    private EntityManager em;

    @EJB
    private MailService mailService;

    /** What became of one attempt to confirm an address. */
    public enum Outcome {

        /** The code was right. The address is confirmed and the account usable. */
        VERIFIED,

        /** The code was wrong. The same code can be tried again, up to the cap. */
        WRONG_CODE,

        /** The code was right once and has run out of time. Ask for another. */
        EXPIRED,

        /** Wrong too many times, so the code is spent. Ask for another. */
        TOO_MANY_ATTEMPTS,

        /** Nothing is waiting to be confirmed: never issued, or already spent. */
        NOTHING_OUTSTANDING,

        /** A code was already accepted for this account. */
        ALREADY_VERIFIED
    }

    /**
     * Sends a code, unless one is already waiting in the inbox.
     *
     * <p>The quiet variant, for the two moments when the application decides a
     * code is needed rather than the person asking for one: registering, and
     * signing in to an account that has not been confirmed. Neither is a place
     * to show a refusal, and neither should replace a code that is still
     * sitting unread in an inbox.
     *
     * <p>Never throws. A person who has just registered must reach the
     * verification page whether or not a message went out; the page offers to
     * send another.
     *
     * @param userId the account to confirm
     * @param requestedIp where the request came from, for the rate limit
     * @return true when a new code was sent, false when one was already
     *         outstanding, the account is confirmed, or the cap was reached
     */
    public boolean issueIfNoneOutstanding(Long userId, String requestedIp) {
        User user = userId == null ? null : em.find(User.class, userId);
        if (user == null || user.isEmailVerified()) {
            return false;
        }
        if (outstandingFor(user) != null) {
            // Already in their inbox, and asking again spends it, so the second
            // copy would invalidate the first while somebody was reading it.
            return false;
        }
        if (codesSentInWindow(user) >= MAX_CODES_PER_HOUR) {
            // Silent, as in the reset flow: the person sees the page and the
            // resend button explains itself if they press it. Logged so an
            // operator can see it happening.
            LOG.warning("Email verification capped for user " + user.getUserId());
            return false;
        }
        send(user, requestedIp);
        return true;
    }

    /**
     * Sends a replacement code because the person asked for one.
     *
     * <p>The loud variant. Somebody who presses "send a new code" is owed an
     * answer, so a refusal here is a message on the page rather than silence.
     *
     * @throws ValidationException when the account is already confirmed, a code
     *                             was sent moments ago, or the hourly cap is
     *                             reached
     */
    public void resend(Long userId, String requestedIp) {
        User user = userId == null ? null : em.find(User.class, userId);
        if (user == null) {
            throw new ValidationException(
                    "That account is no longer available. Create one, or sign in.");
        }
        if (user.isEmailVerified()) {
            throw new ValidationException(
                    "That address is already confirmed. Sign in to carry on.");
        }

        EmailVerification outstanding = outstandingFor(user);
        if (outstanding != null) {
            // A live code exists, so this is a replacement rather than a
            // recovery, and the cooldown applies.
            long waited = Duration.between(outstanding.getRequestedAt(), LocalDateTime.now())
                    .toSeconds();
            if (waited < RESEND_COOLDOWN_SECONDS) {
                long remaining = RESEND_COOLDOWN_SECONDS - waited;
                throw new ValidationException("A code was sent a moment ago. Check your inbox, "
                        + "or try again in " + remaining + " second"
                        + (remaining == 1 ? "" : "s") + ".");
            }
        }

        if (codesSentInWindow(user) >= MAX_CODES_PER_HOUR) {
            throw new ValidationException("That is " + MAX_CODES_PER_HOUR
                    + " codes in the last hour, which is the limit. Try again shortly, and "
                    + "check your spam folder in the meantime.");
        }

        send(user, requestedIp);
    }

    /**
     * Checks a presented code.
     *
     * <p>Returns an outcome rather than throwing, because most outcomes are not
     * failures: a wrong code, an expired code and an exhausted code each want a
     * different sentence and a different next step on the page, and none of
     * them is an error the application did not expect.
     *
     * <p>On success this is the only place an address becomes confirmed, and it
     * also sends the welcome message. That message was held back until now:
     * congratulating somebody on an account they cannot yet use, with a button
     * to a page that would refuse them, is worse than saying nothing.
     *
     * @param userId    the account being confirmed, taken from the session
     *                  rather than the form, so a code can only ever be checked
     *                  against the account that asked for it
     * @param presented what was typed, as typed
     * @return what happened
     */
    public Outcome verify(Long userId, String presented) {
        User user = userId == null ? null : em.find(User.class, userId);
        if (user == null) {
            return Outcome.NOTHING_OUTSTANDING;
        }
        if (user.isEmailVerified()) {
            return Outcome.ALREADY_VERIFIED;
        }

        EmailVerification open = latestUnconsumed(user);
        if (open == null) {
            return Outcome.NOTHING_OUTSTANDING;
        }
        if (!open.isUsable(LocalDateTime.now())) {
            return Outcome.EXPIRED;
        }

        String code = digitsOf(presented);
        if (code == null) {
            // Not six digits, so it was never a guess at the code: a mistyped
            // paste, or a shortened copy. Refused without spending one of the
            // five, because a value that cannot be right is not an attempt and
            // charging for it only punishes somebody who fumbled the paste.
            return Outcome.WRONG_CODE;
        }

        if (!PasswordHasher.verify(code, open.getCodeHash())) {
            int failed = open.recordFailedAttempt();
            if (failed >= MAX_ATTEMPTS) {
                open.setConsumedAt(LocalDateTime.now());
                em.flush();
                LOG.info("Email verification code spent after " + failed
                        + " wrong attempts for user " + user.getUserId());
                return Outcome.TOO_MANY_ATTEMPTS;
            }
            em.flush();
            return Outcome.WRONG_CODE;
        }

        user.setEmailVerifiedAt(LocalDateTime.now());
        spendOutstanding(user);
        em.flush();

        mailService.send(Templates.welcome(user.getEmail(), user.getFullName(), signInUrl()));
        LOG.info("Email address confirmed for user " + user.getUserId());
        return Outcome.VERIFIED;
    }

    /**
     * How many wrong guesses the outstanding code has left.
     *
     * <p>For the sentence on the page: "that code is not right, two tries
     * left". A bare "wrong code" invites the person to keep going until the
     * code dies without ever being told there was a limit.
     *
     * @return the number of tries remaining, or 0 when nothing is outstanding
     */
    public int attemptsRemaining(Long userId) {
        User user = userId == null ? null : em.find(User.class, userId);
        if (user == null) {
            return 0;
        }
        EmailVerification outstanding = outstandingFor(user);
        if (outstanding == null) {
            return 0;
        }
        return Math.max(0, MAX_ATTEMPTS - outstanding.getAttempts());
    }

    /**
     * When the newest unspent code runs out, whether or not it already has.
     *
     * <p>Deliberately not filtered to codes that are still usable, which is the
     * distinction the page needs: a code that has run out of time and a code
     * that was never sent call for different sentences, and
     * {@link #outstandingFor} cannot tell them apart because both are "nothing
     * you can type in right now". The expiry can, because an expired code still
     * has one.
     *
     * <p>This is the page's whole state machine: null means there is nothing to
     * wait for, a time in the past means the code ran out, and a time in the
     * future means it is in the inbox.
     *
     * @return the expiry, or null when no code is outstanding at all
     */
    public LocalDateTime latestCodeExpiry(Long userId) {
        User user = userId == null ? null : em.find(User.class, userId);
        if (user == null || user.isEmailVerified()) {
            return null;
        }
        EmailVerification open = latestUnconsumed(user);
        return open == null ? null : open.getExpiresAt();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Mints a code, stores its hash, and sends it.
     *
     * <p>Spends whatever was outstanding first, so exactly one code is ever
     * live for an account. That is what makes a message found later useless,
     * and it is why {@code email_verifications} carries no unique constraint on
     * the code: two accounts may well hold the same six digits, and only the
     * row belonging to the account being verified is ever compared against.
     *
     * <p>The code never reaches the log. This is a live credential for the few
     * minutes it lasts, and a log is a shared artefact.
     */
    private void send(User user, String requestedIp) {
        spendOutstanding(user);

        String code = newCode();
        EmailVerification verification = new EmailVerification();
        verification.setUser(user);
        verification.setCodeHash(PasswordHasher.hash(code));
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(VALID_MINUTES));
        verification.setRequestedIp(requestedIp);
        em.persist(verification);
        em.flush();

        mailService.send(Templates.verificationCode(user.getEmail(), user.getFullName(), code,
                VALID_MINUTES, MAX_ATTEMPTS));
    }

    /**
     * @return six uniformly random digits, zero-padded
     *
     * <p>{@link SecureRandom#nextInt(int)} over a million values is uniform
     * over the whole space, leading zeros included: 000042 is one of the
     * million, not a shorter code. A per-position {@code nextInt(10)} would
     * give the same distribution and one more way to get it wrong.
     */
    private static String newCode() {
        return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_SPACE));
    }

    /**
     * @return the six digits in the input, or null when there are not six
     *
     * <p>Spaces and dashes are dropped first, because a code pasted out of a
     * message can pick up either, and neither is the person's mistake.
     */
    private static String digitsOf(String presented) {
        if (presented == null) {
            return null;
        }
        String digits = presented.replaceAll("[^0-9]", "");
        return digits.length() == CODE_DIGITS ? digits : null;
    }

    /**
     * The code a person could still present, if there is one.
     *
     * @return the live row, or null when none is outstanding
     */
    private EmailVerification outstandingFor(User user) {
        EmailVerification open = latestUnconsumed(user);
        return open != null && open.isUsable(LocalDateTime.now()) ? open : null;
    }

    /**
     * The most recent code that has not been spent.
     *
     * <p>Found without filtering on the expiry, unlike {@link #outstandingFor},
     * so that an expired code is a thing this can see and report rather than
     * something indistinguishable from a code that was never sent. The
     * difference is the whole of the message the person gets.
     */
    private EmailVerification latestUnconsumed(User user) {
        List<EmailVerification> found = em.createQuery(
                        "SELECT v FROM EmailVerification v "
                                + "WHERE v.user = :user AND v.consumedAt IS NULL "
                                + "ORDER BY v.requestedAt DESC, v.verificationId DESC",
                        EmailVerification.class)
                .setParameter("user", user)
                .setMaxResults(1)
                .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Marks every code for a user as spent.
     *
     * <p>Marked rather than deleted, so a replay is recognisable as a used code
     * instead of an unknown one. Both are refused; the difference is in the
     * record, and it is what makes "my code says it is wrong" answerable.
     */
    private void spendOutstanding(User user) {
        em.createQuery("UPDATE EmailVerification v SET v.consumedAt = :now "
                        + "WHERE v.user = :user AND v.consumedAt IS NULL")
                .setParameter("now", LocalDateTime.now())
                .setParameter("user", user)
                .executeUpdate();
    }

    /** @return how many codes this account has been sent in the last hour */
    private long codesSentInWindow(User user) {
        Long recent = em.createQuery(
                        "SELECT COUNT(v) FROM EmailVerification v "
                                + "WHERE v.user = :user AND v.requestedAt > :since", Long.class)
                .setParameter("user", user)
                .setParameter("since", LocalDateTime.now().minusMinutes(WINDOW_MINUTES))
                .getSingleResult();
        return recent == null ? 0 : recent;
    }

    /**
     * The absolute URL of the sign-in page, for the welcome message.
     *
     * <p>Built from the base URL the container holds, which
     * {@code scripts/glassfish-mail.sh} sets from {@code TM_APP_BASE_URL}. It
     * cannot be derived from the request: the message is composed on another
     * thread, after the request that caused it has been answered.
     */
    private String signInUrl() {
        String base = mailService.getBaseUrl();
        return base.endsWith("/") ? base + "login.xhtml" : base + "/login.xhtml";
    }
}
