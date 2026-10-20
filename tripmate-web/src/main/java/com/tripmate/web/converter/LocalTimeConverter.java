package com.tripmate.web.converter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.ConverterException;
import jakarta.faces.convert.FacesConverter;

/**
 * Converts between a {@link LocalTime} and the {@code HH:mm} string a form
 * submits.
 *
 * <p>Needed because Faces 4.0 has no built-in converter for the java.time
 * types; those arrived with Faces 5 in Jakarta EE 11. Without one, a
 * {@code selectOneMenu} whose values are {@code LocalTime} fails conversion on
 * every submit.
 *
 * <p>That failure is worth spelling out, because it is not obvious from the
 * symptom: a conversion failure raises a validation error, and JSF then skips
 * the invoke-application phase entirely. The action method never runs, no
 * exception is logged, and the page simply redisplays. It looks like the button
 * is broken. Rendering {@code p:messages} in the layout is what turns this back
 * into a visible error.
 */
@FacesConverter("localTimeConverter")
public class LocalTimeConverter implements Converter<LocalTime> {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public LocalTime getAsObject(FacesContext context, UIComponent component, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim(), HH_MM);
        } catch (DateTimeParseException malformed) {
            throw new ConverterException(new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    "Not a time",
                    "\"" + value + "\" is not a time of day. Use HH:mm, for example 09:30."));
        }
    }

    @Override
    public String getAsString(FacesContext context, UIComponent component, LocalTime value) {
        return value == null ? "" : value.format(HH_MM);
    }
}
