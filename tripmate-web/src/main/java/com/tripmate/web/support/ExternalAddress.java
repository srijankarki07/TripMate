package com.tripmate.web.support;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * How the outside world reaches this deployment.
 *
 * <p>Needed wherever the application has to write an absolute address of its
 * own, which today is the calendar feed's subscribe address and nothing else.
 * The address is pasted into another product, so a relative one is no use: that
 * product has no idea what this application's host is.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Behind a tunnel or a reverse proxy, the request the container sees is not
 * the request the reader made. The proxy terminates TLS and forwards plain
 * HTTP to a port on this machine, so {@code getScheme()} says {@code http} and
 * {@code getServerPort()} says the local port, while the reader typed
 * {@code https://} and a hostname that is not this machine's. An address built
 * from those would read {@code http://some-tunnel.example:8080/...}, which is
 * not an address anybody can fetch.
 *
 * <p>The proxy does say what the reader asked for, in {@code X-Forwarded-Proto}
 * and {@code X-Forwarded-Host}. Reading them is the fix, and it is opt-in: a
 * request can carry those headers whether or not anything in front of the
 * application set them, so honouring them by default would let any caller tell
 * the application what host to name. That is not dangerous on its own, since
 * the address is only ever shown back to the caller who asked for it, but it is
 * a lie the application would be repeating, and lies about where a request came
 * from are worth not telling by accident.
 *
 * <p>So it is a deployment fact, declared the same way the other ones are: a
 * system property written by {@code scripts/glassfish-setup.sh} from
 * {@code TM_TRUST_PROXY}. Off unless an operator says otherwise.
 *
 * <h2>And the other direction</h2>
 *
 * <p>{@link #clientAddress} is the one thing here about the address a request
 * came <em>from</em> rather than the address this deployment answers on. It
 * belongs beside the rest because it is the same question asked the other way
 * round, and because both forms that send mail need one answer rather than two
 * copies of one.
 */
public final class ExternalAddress {

    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String FORWARDED_HOST = "X-Forwarded-Host";

    private ExternalAddress() {
        // Static helpers; not instantiable.
    }

    /**
     * The base an absolute address is built from: scheme, host, and a port only
     * when it is not the one the scheme implies.
     *
     * @param request    the request being answered
     * @param trustProxy true when a proxy in front is known to set the
     *                   forwarded headers, so they may be believed
     * @return for example {@code https://trips.example.com}, with no trailing
     *         slash
     */
    public static String base(HttpServletRequest request, boolean trustProxy) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();

        if (trustProxy) {
            String forwardedProto = first(request.getHeader(FORWARDED_PROTO));
            if (forwardedProto != null) {
                scheme = forwardedProto;
                // The forwarded host may or may not carry a port, and the port
                // this request arrived on is the proxy's, not the reader's, so
                // it stops being right the moment the scheme does.
                port = defaultPort(scheme);
            }

            String forwardedHost = first(request.getHeader(FORWARDED_HOST));
            if (forwardedHost != null) {
                int portAt = forwardedHost.lastIndexOf(':');
                // A bracketed IPv6 literal is full of colons, so the only colon
                // that can start a port is one after the closing bracket.
                boolean hasPort = portAt > 0
                        && (!forwardedHost.startsWith("[")
                                || forwardedHost.lastIndexOf(']') < portAt);
                host = hasPort ? forwardedHost.substring(0, portAt) : forwardedHost;
                if (hasPort) {
                    port = port(forwardedHost.substring(portAt + 1), defaultPort(scheme));
                }
            }
        }

        StringBuilder base = new StringBuilder();
        base.append(scheme).append("://").append(host);
        if (port > 0 && port != defaultPort(scheme)) {
            base.append(':').append(port);
        }
        return base.toString();
    }

    /**
     * Whether the address this deployment answers on is one a server on the
     * internet could reach.
     *
     * <p>Google Calendar, Apple Calendar and Outlook fetch a subscribed
     * calendar from their own machines, so an address on this one cannot work
     * and the error they report says nothing about why. This asks the question
     * so the page can explain instead.
     *
     * <p>Deliberately conservative. It recognises the names and the private
     * ranges that are plainly this machine or a private network, and it cannot
     * catch a public hostname that resolves to a private address from the
     * outside. The note it drives is written to cover that case too.
     *
     * @param trustProxy true when the forwarded headers may be believed, which
     *                   matters here: behind a tunnel the request's own host is
     *                   the tunnel's, and that is reachable even though the
     *                   header the reader's browser sent says localhost
     * @return true when the address might be reachable from the internet
     */
    public static boolean isReachableFromInternet(HttpServletRequest request, boolean trustProxy) {
        String address = base(request, trustProxy);
        int schemeEnd = address.indexOf("://");
        String name = (schemeEnd < 0 ? address : address.substring(schemeEnd + 3)).toLowerCase(Locale.ROOT);

        // Strip the port, in either of the two shapes a host can take.
        if (name.startsWith("[")) {
            int close = name.indexOf(']');
            if (close > 0) {
                name = name.substring(1, close);
            }
        } else {
            int colon = name.lastIndexOf(':');
            if (colon > 0) {
                name = name.substring(0, colon);
            }
        }

        if (name.equals("localhost") || name.endsWith(".localhost")
                || name.equals("127.0.0.1") || name.equals("::1") || name.equals("0.0.0.0")) {
            return false;
        }
        return !(name.startsWith("10.")
                || name.startsWith("192.168.")
                || name.startsWith("169.254.")
                || name.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*"));
    }

    /**
     * The address the request came from, for the record and the rate limit.
     *
     * <p>The socket address, not {@code X-Forwarded-For}. A forwarding header
     * can be set by any caller, so believing one without a proxy in front would
     * let a request present a new address every time it asked, which is exactly
     * what a limit keyed on the address exists to stop. Behind a real proxy
     * this is the line that would change, and it would change the same way
     * {@link #base} does: a deployment fact, off by default.
     *
     * <p>Truncated to the 45 characters the columns allow, which is the
     * longest a textual IPv6 address with an embedded IPv4 tail can be. A
     * length check rather than a cast, because what
     * {@code getRemoteAddr} returns for a client on a network it cannot
     * identify is the container's business, not this method's.
     *
     * @return the address, or null when the request cannot supply one
     */
    public static String clientAddress(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String address = request.getRemoteAddr();
        return address == null ? null : address.substring(0, Math.min(45, address.length()));
    }

    /**
     * The first entry of a comma-separated forwarded header.
     *
     * <p>Each proxy appends its own value, so the list grows as a request
     * travels and the first entry is the one the reader's own client sent. The
     * rest describe hops this application has no business naming.
     *
     * @return the value, or null when the header is absent or empty
     */
    private static String first(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.split(",", 2)[0].trim();
        return value.isEmpty() ? null : value;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static int port(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notAPort) {
            return fallback;
        }
    }
}
