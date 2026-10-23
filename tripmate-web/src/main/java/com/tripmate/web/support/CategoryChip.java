package com.tripmate.web.support;

import java.io.Serializable;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * How an expense category is drawn.
 *
 * <p>A category is one of a fixed set, so it gets a key rather than a number: a
 * chip whose colour says <em>which</em> category this is, the same way the
 * position chips beside a balance say which way it runs. The colour and the
 * icon are both decided here, in one place, so the same category cannot come
 * out looking like two different things on two pages.
 *
 * <p>An expense's category is free text as well as a fixed choice: the select
 * on the expense form is editable, so a reader can type one of their own. Those
 * take the neutral slot rather than a colour of their own, because there is no
 * fixed set of them to colour.
 */
@Named
@ApplicationScoped
public class CategoryChip implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The six category slots, in the order the stylesheet defines them. */
    private static final String[] SLOTS = {"food", "transport", "accommodation",
            "activity", "shopping", "other"};

    /** A PrimeIcons name per slot, so the colour is not the only cue. */
    private static final String[] ICONS = {"pi-apple", "pi-car", "pi-home",
            "pi-ticket", "pi-shopping-bag", "pi-tag"};

    /**
     * The classes for the chip.
     *
     * @param category what the expense was filed under, which may be null or
     *                 something the reader typed
     * @return the class list, always including the base class
     */
    public String css(String category) {
        return "tm-cat tm-cat-" + slot(category);
    }

    /**
     * The icon for the chip.
     *
     * @param category as above
     * @return a PrimeIcons class
     */
    public String icon(String category) {
        return "pi " + ICONS[index(category)];
    }

    private static String slot(String category) {
        return SLOTS[index(category)];
    }

    private static int index(String category) {
        if (category == null) {
            return SLOTS.length - 1;
        }
        String key = category.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < SLOTS.length - 1; i++) {
            if (SLOTS[i].equals(key)) {
                return i;
            }
        }
        return SLOTS.length - 1;
    }
}
