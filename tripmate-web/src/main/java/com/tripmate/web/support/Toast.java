package com.tripmate.web.support;

import java.io.Serializable;

/**
 * One message waiting to be shown as a toast.
 *
 * <p>A class with getters rather than a record, for the reason the rest of this
 * application uses classes in views: EL resolves properties by the JavaBean
 * convention, and a record's {@code kind()} accessor is not a property, so the
 * component would render empty.
 *
 * <p>Serializable because a toast raised before a redirect is carried across it
 * in flash scope, and flash scope is stored in the session.
 */
public final class Toast implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How a toast reads, which drives its colour, icon and dismissal. */
    public enum Kind {
        SUCCESS("pi-check-circle", "status"),
        INFO("pi-info-circle", "status"),
        WARNING("pi-exclamation-triangle", "alert"),
        ERROR("pi-times-circle", "alert");

        private final String icon;
        private final String ariaRole;

        Kind(String icon, String ariaRole) {
            this.icon = icon;
            this.ariaRole = ariaRole;
        }

        /**
         * @return the lower-case name, used as a CSS modifier and by the script
         */
        public String getStyleClass() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * @return the PrimeIcons class carrying this severity's meaning
         */
        public String getIcon() {
            return icon;
        }

        /**
         * The ARIA role for a toast of this severity.
         *
         * <p>A success is announced politely, because it is a confirmation and
         * interrupting the reader to say so would be rude. A warning or an error
         * is an alert, because it needs attention now.
         *
         * <p>The role is not the only carrier of meaning: the icon and the
         * coloured edge both differ too, so the severity survives for someone
         * who cannot distinguish the colours.
         */
        public String getAriaRole() {
            return ariaRole;
        }
    }

    private final Kind kind;
    private final String text;

    /**
     * How long this toast stays on screen, in milliseconds.
     *
     * <p>Successes and plain information leave on their own after five seconds,
     * because they are confirmations of something the person just did and
     * already expects. Warnings and errors stay until dismissed: an error that
     * disappears while the reader is looking at the field it refers to is worse
     * than no message at all.
     */
    private final int lifeMillis;

    public Toast(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
        this.lifeMillis = (kind == Kind.SUCCESS || kind == Kind.INFO) ? 5000 : 0;
    }

    public Kind getKind() {
        return kind;
    }

    /** @return the lower-case severity, for the CSS modifier and the script */
    public String getStyleClass() {
        return kind.getStyleClass();
    }

    /** @return the PrimeIcons class for this severity */
    public String getIcon() {
        return kind.getIcon();
    }

    /** @return the ARIA role for this severity */
    public String getAriaRole() {
        return kind.getAriaRole();
    }

    public String getText() {
        return text;
    }

    /**
     * @return milliseconds before auto-dismissal, or zero to stay until closed
     */
    public int getLifeMillis() {
        return lifeMillis;
    }

    /** @return whether this toast dismisses itself */
    public boolean isAutoDismiss() {
        return lifeMillis > 0;
    }
}
