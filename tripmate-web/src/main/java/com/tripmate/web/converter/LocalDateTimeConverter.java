package com.tripmate.web.converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.ConverterException;
import jakarta.faces.convert.FacesConverter;

/**
 * Renders a {@link LocalDateTime} as a date and a time a person can read.
 *
 * <h2>Why this has to exist at all</h2>
 *
 * Faces has no converter for any {@code java.time} type. They arrived in Faces 5
 * with Jakarta EE 11, and this application is on Faces 4, so
 * {@code f:convertDateTime} cannot be pointed at one: it handles
 * {@code java.util.Date} only.
 *
 * <p>Without a converter a bound value falls through to the EL resolver and is
 * rendered with {@code toString()}. For a {@code LocalDate} that happens to be
 * harmless, because {@code toString()} is already {@code 2026-08-16}, and for a
 * {@code LocalTime} likewise, {@code 07:55}. It is only {@code LocalDateTime}
 * that gives itself away, because its {@code toString()} is the ISO-8601
 * interchange form, {@code 2026-08-16T07:55}, with a {@code T} in the middle
 * that no reader should ever have been shown.
 *
 * <p>That is why exactly one column in the application was reported as looking
 * wrong while every other date looked fine, and why the fix is a converter
 * rather than a change to the one column: three repayments tables render a
 * {@code settledAt} the same way and are one seed row away from showing a
 * {@code T} of their own.
 *
 * <h2>No time zone conversion, deliberately</h2>
 *
 * The value is a {@code LocalDateTime}, which is a wall-clock reading with no
 * zone attached, and the column it came from was written with
 * {@code LocalDateTime.now()} off the server's default zone. There is no
 * instant here to convert and no zone stored to convert it from, so shifting
 * one in would invent a difference rather than correct one: the same figure
 * would render differently depending on who asked, for a value that never
 * claimed to be absolute.
 *
 * @see LocalTimeConverter the same idea for a time of day
 */
@FacesConverter("localDateTimeConverter")
public class LocalDateTimeConverter implements Converter<LocalDateTime> {

    /**
     * The display pattern: "16 Aug 2026, 07:55".
     *
     * <p>Day before month, because the surrounding dates render as
     * {@code yyyy-MM-dd} and a reader who has just seen {@code 2026-08-16}
     * should not have to decide whether {@code 08/16} means August or the 8th
     * of an unnamed month. The month is a short name rather than a number for
     * the same reason.
     *
     * <p>Minutes are always shown, including on the hour. Dropping them when
     * they are zero would make two timestamps in one column different widths
     * and imply a precision the value does not have.
     *
     * <p>The locale is pinned rather than taken from the request. The pattern is
     * a fixed English format, and letting the default locale choose the month
     * name would render one column in a different language from the rest of the
     * page.
     */
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    /**
     * Parses the display form back to a value.
     *
     * <p>Not used by any field today, since nothing in the application asks a
     * person to type a date and a time. Implemented anyway because the interface
     * requires it, and because a converter with a working
     * {@code getAsString} and a throwing {@code getAsObject} is a trap for
     * whoever first binds one to an input.
     *
     * @param context   the current request
     * @param component the component the value came from
     * @param value     the submitted text, which may be empty
     * @return the parsed value, or null when the text is empty
     * @throws ConverterException if the text is present but not in the format
     */
    @Override
    public LocalDateTime getAsObject(FacesContext context, UIComponent component, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DISPLAY);
        } catch (DateTimeParseException notADateTime) {
            // The message is written for the person reading it, not for the log:
            // echoing the pattern is more useful than echoing the input, which
            // is already on screen next to the message.
            throw new ConverterException(new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    "Not a date and time",
                    "Enter a date and time as, for example, 16 Aug 2026, 07:55."));
        }
    }

    /**
     * Renders a value for display.
     *
     * @param context   the current request
     * @param component the component being rendered
     * @param value     the value, which may be null
     * @return the formatted date and time, or an empty string for null
     */
    @Override
    public String getAsString(FacesContext context, UIComponent component, LocalDateTime value) {
        // Empty rather than "null", and empty rather than a dash: a component
        // that renders a dash cannot be told apart from one whose value is
        // genuinely a dash, and no column here wants a placeholder.
        return value == null ? "" : DISPLAY.format(value);
    }
}
