#!/usr/bin/env python3
"""Regenerate the map of Nepal drawn on the public home page.

    python3 scripts/make-landing-map.py

The home page shows a small map of Nepal with the trips from the sample data
filled in on it, and each one answering to a pointer the way the real map does.
That map is inline SVG, because a raster would be one fixed set of colours and
would not follow the theme, and because the shapes have to be placed by real
coordinates rather than nudged into position by eye.

None of it could be drawn by hand. A country outline that is nearly right is
worse than no outline at all, and the same goes for the districts inside it: the
shape of Nepal is its most recognisable feature and an invented one reads as
wrong to anybody who knows it. So both are real data, simplified until they are
small enough to sit in a page, and this script is what produced the numbers now
in the page. Re-run it only if the drawing needs to change, which is to say
almost never.

    source      Nominatim, from OpenStreetMap. The country as a GeoJSON polygon
                from a search; the provinces the same way, by name; and each
                place as the administrative area its own coordinates fall
                inside, from a reverse lookup. That last call is the same one
                the application makes when a trip is placed on the real map, so
                a filled district here is a filled district there.
    simplify    Douglas-Peucker on the projected rings: about 3 units for the
                country, which is a little under three pixels at the size it is
                drawn, and about 0.8 units for everything inside it, which is
                all the detail a shape the size of a thumbnail can carry.
    project     Equirectangular, with the longitude scaled by cos(latitude), so
                the country is not stretched sideways at this latitude. At
                Nepal's extent the error against a proper Mercator is under a
                pixel, which is not worth the arithmetic.
    fit         Normalised into a viewBox whose width is fixed and whose height
                follows the country's own aspect ratio, so the shape is never
                distorted to fill a box. Everything inside is fitted through
                the same transform, so it lands inside the border rather than
                beside it.

Why provinces and not districts. Nepal has seven provinces and seventy-seven
districts, and the district boundaries are the ones a map at this size would
show if it could. It cannot: the drawing is about three hundred and eighty
pixels wide, so a district comes out around fifteen pixels across and the
country becomes a mesh rather than a map, which is worse than no internal lines
at all. The provinces divide it into seven readable regions. The three trips
are still drawn as their own districts, because those are the shapes the
application itself fills.

It prints the SVG paths and the box they belong in. Everything else on the
drawing, including what each shape says when it is pointed at, is in the page.

The output is OpenStreetMap data, which is ODbL: the page credits
"OpenStreetMap contributors" beneath the drawing, and that credit is a licence
condition rather than a courtesy.
"""

import json
import math
import time
import urllib.parse
import urllib.request

# A descriptive User-Agent is a condition of using Nominatim, and a request
# without one is refused.
USER_AGENT = "TripMate-landing-map/1.0 (Jakarta EE coursework project)"
ENDPOINT = "https://nominatim.openstreetmap.org/"

# Nominatim allows one request a second, and this makes eleven of them: the
# country, the seven provinces, and one reverse lookup per trip. The whole
# script therefore takes about fifteen seconds and never risks being throttled.
PAUSE_SECONDS = 1.2

# The latitude the longitude scale is corrected for: roughly Nepal's middle.
REFERENCE_LATITUDE = 28.4
REFERENCE_LONGITUDE = 84.0

# How hard to simplify, in viewBox units rather than degrees, so the number
# means the same thing whatever the underlying data looks like.
COUNTRY_DETAIL = 3.0
INNER_DETAIL = 0.8

# The drawn width. The height comes out of the country's aspect ratio, which
# is about 1.76:1 here.
VIEWBOX_WIDTH = 420.0

# Nepal's first-level divisions, which is what the internal lines are.
PROVINCES = [
    "Koshi Province",
    "Madhesh Province",
    "Bagmati Province",
    "Gandaki Province",
    "Lumbini Province",
    "Karnali Province",
    "Sudurpashchim Province",
]

# The trips in database/seed.sql, at the coordinates that file stores them.
PLACES = [
    ("Pokhara Weekend", 28.2096, 83.9856),
    ("Everest Base Camp Trek", 27.6869, 86.7300),
    ("Chitwan Jungle Safari", 27.5291, 84.3542),
]


def fetch(url):
    """One Nominatim call, with the pause its usage policy asks for."""
    request = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept-Language": "en",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    time.sleep(PAUSE_SECONDS)
    return payload


def rings_of(geojson):
    """Every outer ring in a GeoJSON Polygon or MultiPolygon."""
    if not geojson:
        return []
    if geojson.get("type") == "Polygon":
        return [geojson["coordinates"][0]]
    if geojson.get("type") == "MultiPolygon":
        return [polygon[0] for polygon in geojson["coordinates"]]
    return []


def clean(ring):
    """A ring as (longitude, latitude) pairs, without the repeated last point.

    GeoJSON closes a ring by repeating its first point. Left in, it gives the
    simplifier a zero-length segment to measure against and the whole shape
    collapses to two points.
    """
    points = [(float(x), float(y)) for x, y in ring]
    if len(points) > 1 and points[0] == points[-1]:
        points = points[:-1]
    return points


def project(longitude, latitude):
    """Equirectangular, corrected for the width of a degree at this latitude."""
    return (
        (longitude - REFERENCE_LONGITUDE) * math.cos(math.radians(REFERENCE_LATITUDE)),
        -(latitude - REFERENCE_LATITUDE),
    )


def douglas_peucker(points, epsilon):
    """The standard line simplification, on an open polyline."""
    if len(points) < 3:
        return list(points)
    (x1, y1), (x2, y2) = points[0], points[-1]
    farthest, index = -1.0, 0
    for i in range(1, len(points) - 1):
        x0, y0 = points[i]
        numerator = abs((y2 - y1) * x0 - (x2 - x1) * y0 + x2 * y1 - y2 * x1)
        denominator = math.hypot(y2 - y1, x2 - x1)
        distance = (
            numerator / denominator
            if denominator > 1e-12
            else math.hypot(x0 - x1, y0 - y1)
        )
        if distance > farthest:
            farthest, index = distance, i
    if farthest > epsilon:
        return (
            douglas_peucker(points[: index + 1], epsilon)[:-1]
            + douglas_peucker(points[index:], epsilon)
        )
    return [points[0], points[-1]]


def simplify_ring(points, epsilon):
    """The same, on a closed ring.

    Run directly on a ring, the algorithm measures every point against the
    segment joining the first to the last, and on a closed ring those are the
    same point: the segment has no length and every distance comes out as
    zero, so the whole shape simplifies away. Splitting the ring at the point
    furthest from its start gives two open arcs with distinct endpoints, which
    is what the algorithm expects.
    """
    start = points[0]
    furthest = max(
        range(len(points)),
        key=lambda i: (points[i][0] - start[0]) ** 2 + (points[i][1] - start[1]) ** 2,
    )
    first = douglas_peucker(points[: furthest + 1], epsilon)
    second = douglas_peucker(points[furthest:] + [start], epsilon)
    return first[:-1] + second[:-1]


def main():
    country = fetch(ENDPOINT + "search?" + urllib.parse.urlencode({
        "q": "Nepal",
        "format": "json",
        "polygon_geojson": 1,
        "polygon_threshold": 0.01,
        "limit": 1,
    }))
    if not country:
        raise SystemExit("Nominatim returned nothing for Nepal")
    country_ring = clean(rings_of(country[0]["geojson"])[0])
    country_projected = [project(x, y) for x, y in country_ring]

    # The box comes from the unsimplified ring, so simplifying the border
    # cannot shift the frame everything else is placed in.
    xs = [p[0] for p in country_projected]
    ys = [p[1] for p in country_projected]
    min_x, max_x, min_y, max_y = min(xs), max(xs), min(ys), max(ys)
    scale = VIEWBOX_WIDTH / (max_x - min_x)
    height = round((max_y - min_y) * scale)

    def to_svg(point):
        x, y = point
        return (round((x - min_x) * scale, 1), round((y - min_y) * scale, 1))

    def path_from(rings):
        """One path per shape, and a shape may be more than one ring.

        A province that owns an island or an exclave arrives as a MultiPolygon,
        and keeping only its largest ring would silently erase the rest of it.
        Subpaths in one `d` are one path with one set of styles, which is what
        is wanted here.
        """
        return " ".join(
            "M" + " ".join(f"{x} {y}" for x, y in (to_svg(p) for p in ring)) + "Z"
            for ring in rings
        )

    country_out = simplify_ring(country_projected, COUNTRY_DETAIL / scale)
    print("<!-- the country -->")
    print(f'viewBox="0 0 {int(VIEWBOX_WIDTH)} {height}"')
    print(f"country: {len(country_out)} points")
    print(f'  d="{path_from([country_out])}"')
    print()

    print("<!-- the provinces, drawn as lines inside the border -->")
    for name in PROVINCES:
        found = fetch(ENDPOINT + "search?" + urllib.parse.urlencode({
            "q": name,
            "countrycodes": "np",
            "format": "json",
            "polygon_geojson": 1,
            "polygon_threshold": 0.01,
            "limit": 1,
        }))
        if not found:
            print(f"{name}: nothing returned")
            continue
        rings = [clean(r) for r in rings_of(found[0].get("geojson"))]
        if not rings:
            print(f"{name}: no polygon")
            continue
        simplified = [
            simplify_ring([project(x, y) for x, y in ring], INNER_DETAIL / scale)
            for ring in rings
        ]
        print(f"{name}: {sum(len(r) for r in simplified)} points in {len(simplified)} ring(s)")
        print(f'  d="{path_from(simplified)}"')
    print()

    print("<!-- the trips, drawn as the districts their coordinates fall in -->")
    for name, latitude, longitude in PLACES:
        found = fetch(ENDPOINT + "reverse?" + urllib.parse.urlencode({
            "lat": latitude,
            "lon": longitude,
            "format": "json",
            "polygon_geojson": 1,
            "polygon_threshold": 0.01,
            "zoom": 10,
        }))
        raw = rings_of(found.get("geojson"))
        if not raw:
            print(f"{name}: no boundary returned; it would be a pin on the real map")
            continue
        # The largest ring, in case the lookup answers with an island or an
        # exclave as well as the main shape.
        ring = clean(max(raw, key=len))
        projected = [project(x, y) for x, y in ring]
        simplified = simplify_ring(projected, INNER_DETAIL / scale)
        label_x, label_y = to_svg(project(longitude, latitude))
        print(f'{name}: {len(simplified)} points, label at {label_x} {label_y}')
        print(f'  d="{path_from(simplified)}"')
    print()
    print("Credit the source wherever this is drawn: OpenStreetMap contributors.")


if __name__ == "__main__":
    main()
