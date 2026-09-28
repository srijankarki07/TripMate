package com.tripmate.web.support;

/**
 * Turns a display name into the one or two letters an avatar shows.
 *
 * <p>Its own class rather than a method on the bean that happens to need it
 * first, because three unrelated places draw an avatar now: the navigation bar
 * for the signed-in user, the trip's member list, and the balance position bar.
 * Three copies of this rule would drift, and the drift would be invisible until
 * two avatars for the same person disagreed on the same page.
 *
 * <p>Static, like {@code PasswordHasher} in the business tier and
 * {@code Messages} beside it: this is a formatting rule, not state, so a caller
 * should not need anything injected to use it.
 */
public final class Initials {

    /** What an avatar shows when the name yields nothing usable. */
    private static final String UNKNOWN = "?";

    private Initials() {
    }

    /**
     * The initials for a display name.
     *
     * <p>Derived from the first and last word rather than the first two, so
     * "Srijan Karki" gives "SK" rather than "SR". A single-word name gives one
     * letter, which is the honest answer: there is no second word to take a
     * letter from.
     *
     * <p>Bounded to two characters because that is what the avatar is sized for;
     * a longer string overflows the circle on a narrow screen.
     *
     * @param fullName the display name, which may be null, empty or blank
     * @return one or two upper-case letters, or "?" when the name gives nothing,
     *         and never null
     */
    public static String of(String fullName) {
        if (fullName == null) {
            return UNKNOWN;
        }

        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }

        String[] words = trimmed.split("\\s+");
        StringBuilder initials = new StringBuilder();
        initials.append(firstLetterOrDigit(words[0]));
        if (words.length > 1) {
            initials.append(firstLetterOrDigit(words[words.length - 1]));
        }

        String result = initials.toString().trim().toUpperCase(java.util.Locale.ROOT);
        return result.isEmpty() ? UNKNOWN : result;
    }

    /**
     * The first character of a word that is actually a letter or a digit.
     *
     * <p>Names arrive with punctuation attached often enough to matter: a
     * leading quote or bracket would otherwise become the initial, and an
     * avatar reading {@code "} is not what anybody means by their initials.
     *
     * @return the character, or a space when the word has none
     */
    private static char firstLetterOrDigit(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                return word.charAt(i);
            }
        }
        return ' ';
    }
}
