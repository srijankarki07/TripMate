---
version: alpha
name: Airbnb
website: "https://www.airbnb.com"
description: A warm, generous consumer marketplace anchored on a clean white canvas and Airbnb Rausch (#ff385c), the single brand voltage that carries every primary CTA, search-button orb, and rating dot. Type runs Airbnb Cereal VF at modest weights — display sits at 22–28px in weight 500/600 rather than the heavy 700+ that fintech and enterprise systems use; the brand trusts photography and generous whitespace over typographic muscle. Three product entries (Homes, Experiences, Services) sit in the top nav with hand-illustrated 32-icon glyphs and "NEW" badges, signaling a marketplace expansion rather than a feature dump. Pill-shaped search bars (`{rounded.full}`), softly rounded property cards (`{rounded.lg}` ~14px), and 32px button radii read as friendly and human — there is no hard corner anywhere except the body grid.

seo:
  title: "Airbnb Design System for React — Rausch, Cereal VF, and 33 components"
  metaDescription: "Airbnb's design system as a DESIGN.md file. Rausch #ff385c, Cereal VF, 23 colors, 33 components. For React, Next.js, and AI tools."
  tags:
    - "Travel & Hospitality"
    - "Marketplaces"
  highlights:
    - "Single accent voltage — Rausch (#ff385c) used in just 3 of 33 components, never as a secondary"
    - "Modest type weights — display headlines sit at 22–28px / 500–700, not the heavy weights fintech leans on"
    - "Soft 14px base radius — every interactive surface is rounded; the only sharp edges are body grid lines"
    - "No dark mode in mainline marketing — photography and pure white #ffffff canvas carry the visual weight"
    - "One typographically loud moment — the 64px rating display on listing pages, the system's peak trust signal"
  lastUpdated: "2026-05-12"
  author:
    name: "Dov Azencot"
    url: "https://x.com/dovazencot"
  opening: |
    Airbnb's design system is built around one idea: restraint. One brand color (Rausch, #ff385c). One custom typeface (Airbnb Cereal VF). One soft 14px corner on most surfaces. No second accent in the marketing site. The brand leans on photography and white space, not on bold type.

    This page packages all of that into a single DESIGN.md file. Inside: 23 colors, 18 type styles, 9 spacing values, 9 corner radii, and 33 components — every piece you need to build something that looks like Airbnb.

    Download the file and feed it to Claude, Cursor, or GitHub Copilot. The AI writes React components and Tailwind classes that match Airbnb's voice — not a generic e-commerce theme. Or just use it as a reference for your own work.
  related:
    - href: "https://github.com/google-labs-code/design.md"
      title: "The DESIGN.md specification"
      description: "Google Labs' open spec for machine-readable design system files — the format this page is built on."
    - href: "/design"
      title: "Browse all design systems"
      description: "The full directory of DESIGN.md files on shadcn.io, with live mockups for each."
    - href: "/blocks"
      title: "React blocks for shadcn/ui"
      description: "Production-ready hero, pricing, CTA, and dashboard sections built with the same Tailwind + shadcn primitives."
  questions:
    - id: "primary-color"
      title: "What is Airbnb's primary brand color?"
      answer: "Airbnb's brand color is Rausch — #ff385c, a coral-leaning red somewhere between fuchsia and tomato. It's used sparingly across just three of the system's 33 components: primary CTA buttons, the circular search orb at the right edge of the search bar, and the heart save state on property cards. Most pages are 90% white plus near-black ink, with Rausch acting as the visual voltage that focuses the eye on one action at a time."
    - id: "dark-mode"
      title: "Does Airbnb's design system have a dark mode?"
      answer: "No — Airbnb's public marketing and product surfaces are light-only. Canvas is pure #ffffff, surfaces step through three near-white fills (#f7f7f7, #ffffff card, #f2f2f2 strong), and text is deep ink #222222 rather than pure black. A native dark mode exists in the iOS and Android apps, but it isn't represented in this DESIGN.md, which captures the public web experience."
    - id: "typography"
      title: "What typography does Airbnb use, and what should I use if Cereal isn't available?"
      answer: "Airbnb runs Airbnb Cereal VF, a custom variable typeface licensed only to Airbnb. The spec walks through 18 type tokens at modest weights (400–700), with display headlines deliberately small (22–28px) and a single typographically loud moment — the 64px rating display on listing pages. Inter is the closest open-source substitute; adjust line-heights down by ~2% to match Cereal's slightly tighter cap height."
    - id: "modest-weights"
      title: "Why does Airbnb use such modest display weights?"
      answer: "The brand trusts photography to carry visual weight, not typography. A homepage h1 at 28px / 700 would feel small on a typical SaaS page, but Airbnb tucks it under the search bar and lets the city collage and property cards above it pull the eye. The single loud typographic moment is the listing-page rating display (64px / 700) — rating numbers are a peak trust signal, so they get the loudest treatment in the system."
    - id: "use-in-project"
      title: "Can I use this DESIGN.md to build my own React marketplace?"
      answer: "Yes — the file is designed to be fed into Claude, Cursor, or any AI tool that reads structured design tokens. The agent will reproduce Airbnb's restraint (single accent, soft radii, modest type) rather than a generic shadcn theme. You can also reference the tokens directly: every color, type style, radius, and spacing value in the spec is a quoted value you can paste into Tailwind config, CSS variables, or your own component library."
    - id: "known-gaps"
      title: "What's missing from this DESIGN.md spec?"
      answer: "A handful of things, documented in the Known Gaps section of the file: hover-state colors (the system has a single subtle elevation lift on cards that's hard to extract reliably), loading skeletons, the search-results map tile style, full input error-state combinations, and the sub-brand palettes for Airbnb Luxe (#460479) and Plus (#92174d), which live on separate sub-domains. The spec captures roughly 95% of the public marketing and product surfaces."

colors:
  primary: "#ff385c"
  primary-active: "#e00b41"
  primary-disabled: "#ffd1da"
  primary-error-text: "#c13515"
  primary-error-text-hover: "#b32505"
  luxe: "#460479"
  plus: "#92174d"
  ink: "#222222"
  body: "#3f3f3f"
  muted: "#6a6a6a"
  muted-soft: "#929292"
  hairline: "#dddddd"
  hairline-soft: "#ebebeb"
  border-strong: "#c1c1c1"
  canvas: "#ffffff"
  surface-soft: "#f7f7f7"
  surface-card: "#ffffff"
  surface-strong: "#f2f2f2"
  on-primary: "#ffffff"
  on-dark: "#ffffff"
  legal-link: "#428bff"
  star-rating: "#222222"
  scrim: "#000000"

typography:
  display-xl:
    fontFamily: "'Airbnb Cereal VF', Circular, -apple-system, system-ui, Roboto, 'Helvetica Neue', sans-serif"
    fontSize: 28px
    fontWeight: 700
    lineHeight: 1.43
    letterSpacing: 0
  display-lg:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 22px
    fontWeight: 500
    lineHeight: 1.18
    letterSpacing: -0.44px
  display-md:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 21px
    fontWeight: 700
    lineHeight: 1.43
    letterSpacing: 0
  display-sm:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 20px
    fontWeight: 600
    lineHeight: 1.20
    letterSpacing: -0.18px
  title-md:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: 0
  title-sm:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 16px
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: 0
  rating-display:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 64px
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: -1px
  body-md:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  body-sm:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.43
    letterSpacing: 0
  caption:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.29
    letterSpacing: 0
  caption-sm:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.23
    letterSpacing: 0
  badge:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 11px
    fontWeight: 600
    lineHeight: 1.18
    letterSpacing: 0
  micro-label:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 12px
    fontWeight: 700
    lineHeight: 1.33
    letterSpacing: 0
  uppercase-tag:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 8px
    fontWeight: 700
    lineHeight: 1.25
    letterSpacing: 0.32px
    textTransform: uppercase
  button-md:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 16px
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: 0
  button-sm:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.29
    letterSpacing: 0
  link:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.43
    letterSpacing: 0
  nav-link:
    fontFamily: "'Airbnb Cereal VF', Circular, sans-serif"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: 0

rounded:
  none: 0px
  xs: 4px
  sm: 8px
  md: 14px
  lg: 20px
  xl: 32px
  full: 9999px

spacing:
  xxs: 2px
  xs: 4px
  sm: 8px
  md: 12px
  base: 16px
  lg: 24px
  xl: 32px
  xxl: 48px
  section: 64px

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button-md}"
    rounded: "{rounded.sm}"
    padding: 14px 24px
    height: 48px
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
  button-primary-disabled:
    backgroundColor: "{colors.primary-disabled}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
  button-secondary:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.button-md}"
    rounded: "{rounded.sm}"
    padding: 13px 23px
    height: 48px
  button-tertiary-text:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.button-md}"
  button-pill-rausch:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button-sm}"
    rounded: "{rounded.full}"
    padding: 10px 20px
  search-orb:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.full}"
    height: 48px
  icon-button-circle:
    backgroundColor: "{colors.surface-strong}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    height: 32px
  icon-button-outline:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    height: 40px
  top-nav:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.nav-link}"
    height: 80px
  product-tab-active:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.nav-link}"
    rounded: "{rounded.none}"
  product-tab-inactive:
    backgroundColor: transparent
    textColor: "{colors.muted}"
    typography: "{typography.nav-link}"
  search-bar-pill:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.full}"
    padding: 14px 24px
    height: 64px
  search-field-segment:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.caption}"
    padding: 8px 24px
  category-strip:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.muted}"
    typography: "{typography.button-sm}"
  category-tab-active:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.button-sm}"
    rounded: "{rounded.none}"
  property-card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
  property-card-photo:
    rounded: "{rounded.md}"
  experience-card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.title-md}"
    rounded: "{rounded.md}"
  city-link-block:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.title-sm}"
  rating-display-card:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.rating-display}"
  guest-favorite-badge:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.badge}"
    rounded: "{rounded.full}"
    padding: 4px 10px
  new-tag:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.uppercase-tag}"
    rounded: "{rounded.full}"
    padding: 2px 6px
  amenity-row:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    padding: 12px 0
  reviews-card:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
  host-card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    padding: 24px
  reservation-card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
    padding: 24px
  date-picker-day:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.full}"
  date-picker-day-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.full}"
  text-input:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-md}"
    rounded: "{rounded.sm}"
    padding: 14px 12px
    height: 56px
  footer-light:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    padding: 48px 80px
  footer-link:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
  legal-band:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.muted}"
    typography: "{typography.caption-sm}"
---

## Overview

Airbnb is the canonical example of a generous, photography-led consumer marketplace. The base canvas is **pure white** (`{colors.canvas}` — #ffffff) with deep near-black ink (`{colors.ink}` — #222222) for headlines and body, and a single voltage of **Rausch** (`{colors.primary}` — #ff385c) carrying every primary CTA, the search-button orb, the heart save state, and inline brand links. There is no secondary brand color in mainline marketing — the **Luxe purple** (`{colors.luxe}` — #460479) and **Plus magenta** (`{colors.plus}` — #92174d) tokens are sub-brand accents that only appear inside Airbnb Luxe / Plus contexts.

Type runs **Airbnb Cereal VF** (a custom variable font Airbnb licenses), with **Circular** as the historic in-house fallback and a system stack underneath. Cereal sits at modest weights — display headlines render at 22–28px in weight 500–600, not the heavy 700+ weights that financial or enterprise systems lean on. The hero h1 ("Inspiration for future getaways") on the homepage is just 28px / 700, which would feel small on a typical SaaS page; here it works because the layout leans on photography (city collage, property cards) for visual weight rather than typographic muscle.

The shape language is **soft**. Buttons are 8px radius (`{rounded.sm}`), property cards are ~14px (`{rounded.md}`), the search bar is fully pill-shaped (`{rounded.full}`), wishlist hearts and search orbs are circles (`{rounded.full}`), and category strip rounded corners run at 32px (`{rounded.xl}`). There is essentially no hard corner anywhere except the body grid itself — every interactive element is rounded.

**Key Characteristics:**

- Single accent color: `{colors.primary}` (#ff385c — "Rausch") carries every primary CTA, the search orb, the heart save state, and the brand wordmark. Used scarcely — most pages are 90% white + ink with one or two Rausch moments.
- Custom variable type: `Airbnb Cereal VF`. Display weights sit at 500–700, body at 400. Modest weight is intentional — the system trusts photography for visual heft.
- Three-product top nav: Homes, Experiences, Services — each with a hand-illustrated 32px icon and "NEW" badges (`{component.new-tag}`) on the two newer products. Active tab uses an underline rule (`{component.product-tab-active}`).
- Pill-shaped global search bar: white surface, fully rounded (`{rounded.full}`), divided by 1px hairlines into Where / When / Who segments, terminated by a circular Rausch search orb (`{component.search-orb}`).
- Property cards are photo-first: aspect-ratio rectangles with `{rounded.md}` corner clipping, swipeable image carousel, "Guest favorite" floating badge top-left, heart icon top-right, then 4–5 lines of meta beneath.
- Editorial dropdowns (footer, language picker) are clean text columns over the white canvas — no card surface, no shadow.
- The design system caps elevation at one shadow tier (`box-shadow: rgba(0,0,0,0.02) 0 0 0 1px, rgba(0,0,0,0.04) 0 2px 6px, rgba(0,0,0,0.1) 0 4px 8px`) — used on hover-floated cards and search/account dropdowns.
- 8px base spacing system, with major sections at `{spacing.section}` (64px) — generous but not airy enough to feel editorial-magazine; the marketplace density wants more cards per scroll.

## Colors

### Brand & Accent

- **Rausch** (`{colors.primary}` — #ff385c): The single brand color. Used for primary CTA backgrounds (Reserve, Continue), the search orb, the heart save state on property cards, and inline brand links. The most recognizable color in consumer travel.
- **Rausch Active** (`{colors.primary-active}` — #e00b41): The press / pointer-down variant — slightly more saturated. Used on `{component.button-primary-active}`.
- **Rausch Disabled** (`{colors.primary-disabled}` — #ffd1da): A pale tint used on disabled CTAs.
- **Luxe Purple** (`{colors.luxe}` — #460479): Sub-brand accent for Airbnb Luxe. Only appears inside Luxe-branded surfaces — never in mainline marketing.
- **Plus Magenta** (`{colors.plus}` — #92174d): Sub-brand accent for Airbnb Plus. Same scoping as Luxe — sub-product only.

### Surface

- **Canvas** (`{colors.canvas}` — #ffffff): The default page floor for every public page. Airbnb does not have a dark mode on the public web.
- **Surface Soft** (`{colors.surface-soft}` — #f7f7f7): The lightest fill — used on disabled fields, sub-nav hover backgrounds, and the inline search filter band.
- **Surface Strong** (`{colors.surface-strong}` — #f2f2f2): Slightly heavier fill — circular icon-button surface (e.g., the breadcrumb back-arrow and listing toolbar buttons).

### Hairlines & Borders

- **Hairline** (`{colors.hairline}` — #dddddd): The default 1px border tone — search bar dividers, table separators, footer column splitters, card 1px borders.
- **Hairline Soft** (`{colors.hairline-soft}` — #ebebeb): A lighter divider used on long-scrolling editorial body separators.
- **Border Strong** (`{colors.border-strong}` — #c1c1c1): A heavier stroke used on disabled outline buttons and form input outlines after focus.

### Text

- **Ink** (`{colors.ink}` — #222222): The dominant text color on light surfaces. Display headlines, body paragraphs, primary nav links, and most inline link text. Never pure black.
- **Body** (`{colors.body}` — #3f3f3f): A secondary running-text color used inside long-form review and amenity copy where ink would feel too heavy.
- **Muted** (`{colors.muted}` — #6a6a6a): Sub-titles inside city link blocks ("Cottage rentals", "Villa rentals"), inactive product-tab labels, footer category sub-labels, "View all" links.
- **Muted Soft** (`{colors.muted-soft}` — #929292): Disabled link text. Used very sparingly.
- **Star Rating** (`{colors.star-rating}` — #222222): The same ink token — Airbnb's star icon and "4.81" rating numbers all render in ink rather than a yellow/gold color, which is a deliberate brand choice (yellow stars feel cheap in travel context).
- **On Primary** (`{colors.on-primary}` — #ffffff): White text on Rausch CTAs.

### Semantic

- **Error** (`{colors.primary-error-text}` — #c13515): Inline error text for form validation. Distinct from Rausch — slightly darker, more saturated red.
- **Error Hover** (`{colors.primary-error-text-hover}` — #b32505): Darkens on link hover.
- **Legal Link Blue** (`{colors.legal-link}` — #428bff): Inline links inside legal copy (Privacy, Terms). Only used inside the legal sub-band.

### Scrim

- **Scrim** (`{colors.scrim}` — #000000 at 50% opacity): The global modal backdrop tone — date picker, login dialog, language picker. Stored as the base hex; opacity is applied at render time.

## Typography

### Font Family

The system runs **Airbnb Cereal VF** for everything — display, body, navigation, captions, microcopy. Fallbacks walk `Circular, -apple-system, system-ui, Roboto, "Helvetica Neue", sans-serif`. **Circular** is the historic in-house typeface still kept as the first non-variable fallback; system stacks back it up.

There is no separate display family. The variable font carries the entire scale.

### Hierarchy

| Token                         | Size | Weight | Line Height | Letter Spacing     | Use                                                            |
| ----------------------------- | ---- | ------ | ----------- | ------------------ | -------------------------------------------------------------- |
| `{typography.rating-display}` | 64px | 700    | 1.1         | -1px               | Listing detail rating display ("4.81")                         |
| `{typography.display-xl}`     | 28px | 700    | 1.43        | 0                  | Homepage h1 ("Inspiration for future getaways")                |
| `{typography.display-lg}`     | 22px | 500    | 1.18        | -0.44px            | Listing detail h1 ("Close to Fethiye Aliyah Bali Beach…")      |
| `{typography.display-md}`     | 21px | 700    | 1.43        | 0                  | Section heads inside listing detail ("What this place offers") |
| `{typography.display-sm}`     | 20px | 600    | 1.20        | -0.18px            | Sub-section titles ("Things to know")                          |
| `{typography.title-md}`       | 16px | 600    | 1.25        | 0                  | City link block titles ("Wilmington", "Athens")                |
| `{typography.title-sm}`       | 16px | 500    | 1.25        | 0                  | Footer column heads ("Support", "Hosting", "Airbnb")           |
| `{typography.body-md}`        | 16px | 400    | 1.5         | 0                  | Default running-text inside listing copy                       |
| `{typography.body-sm}`        | 14px | 400    | 1.43        | 0                  | Card meta lines, dates, prices, distance text                  |
| `{typography.caption}`        | 14px | 500    | 1.29        | 0                  | Search field segment labels ("Where", "When", "Who")           |
| `{typography.caption-sm}`     | 13px | 400    | 1.23        | 0                  | Footer legal line ("© 2026 Airbnb, Inc.")                      |
| `{typography.badge}`          | 11px | 600    | 1.18        | 0                  | "Guest favorite" floating badge text                           |
| `{typography.micro-label}`    | 12px | 700    | 1.33        | 0                  | Card amenity micro-labels ("Inline 6")                         |
| `{typography.uppercase-tag}`  | 8px  | 700    | 1.25        | 0.32px (uppercase) | "NEW" badge on product nav tabs                                |
| `{typography.button-md}`      | 16px | 500    | 1.25        | 0                  | Primary CTA button labels                                      |
| `{typography.button-sm}`      | 14px | 500    | 1.29        | 0                  | Pill button labels (category strip)                            |
| `{typography.link}`           | 14px | 400    | 1.43        | 0                  | Inline body links                                              |
| `{typography.nav-link}`       | 16px | 600    | 1.25        | 0                  | Top product-nav labels (Homes, Experiences, Services)          |

### Principles

Display weights stay modest. The homepage h1 at 28px / 700 is deliberately small — it tucks under the search bar so photography and the city-link grid carry visual hierarchy. The listing-detail h1 at 22px / 500 is even quieter; the listing photo banner does the work above it.

The single typographically loud moment in the entire system is the **rating display** (`{typography.rating-display}` — 64px / 700) on listing pages. That is the only place the system trusts type alone to carry hierarchy — rating numbers are a peak trust signal, so they get the loudest treatment.

### Note on Font Substitutes

If Airbnb Cereal VF and Circular are unavailable, **Inter** is the closest open-source substitute. Adjust display headlines down by ~2% in line-height to match Cereal's slightly tighter cap height; otherwise the proportions transfer cleanly.

## Layout

### Spacing System

- **Base unit:** 4px (with 2px micro-step).
- **Tokens:** `{spacing.xxs}` 2px · `{spacing.xs}` 4px · `{spacing.sm}` 8px · `{spacing.md}` 12px · `{spacing.base}` 16px · `{spacing.lg}` 24px · `{spacing.xl}` 32px · `{spacing.xxl}` 48px · `{spacing.section}` 64px.
- **Section padding (vertical):** `{spacing.section}` (64px) for major page bands; tighter than typical SaaS marketing (80–96px) because marketplace pages need higher card density per scroll.
- **Card internal padding:** `{spacing.lg}` (24px) for `{component.host-card}` and `{component.reservation-card}`; `{spacing.base}` (16px) for property-card meta block; `{spacing.sm}` (8px) for caption / date-row gutters.
- **Gutters:** `{spacing.base}` (16px) between cards in the homepage city grid; `{spacing.lg}` (24px) inside footer column gutters; `{spacing.xs}` (4px) on dense category-strip dividers.

### Grid & Container

- **Max content width:** ~1280px centered on the homepage and editorial pages. Listing detail pages cap closer to 1080px to keep the photo banner and reservation rail readable.
- **City link grid (homepage footer):** 6-column grid at desktop with each cell housing a city name in `{typography.title-md}` and a category sub-label in `{typography.body-sm}` muted.
- **Listing detail:** 2-column with photo / amenity body on the left (~64% width) and a sticky reservation card (`{component.reservation-card}`) on the right (~32%).
- **Footer:** 3-column link list (Support / Hosting / Airbnb) at desktop, collapsing to 1-column on mobile.

### Whitespace Philosophy

The system gives editorial bands 64px of vertical breathing room but compresses card grids — property and city-link cards sit just 16px apart. The contrast is intentional: the page reads as "open hero, dense marketplace below," reinforcing the marketplace nature without overwhelming the visitor at the fold.

## Elevation

The system has essentially **one shadow tier** plus the flat baseline.

- **Flat (no shadow):** Body, hero, footer, all editorial bands — 95% of surfaces.
- **Card hover float:** `box-shadow: rgba(0, 0, 0, 0.02) 0 0 0 1px, rgba(0, 0, 0, 0.04) 0 2px 6px 0, rgba(0, 0, 0, 0.1) 0 4px 8px 0` — applied to property cards on pointer hover, the search bar at rest, and the dropdown menus (account menu, language picker, date picker). This is the single shadow definition in the entire system.
- **Modal scrim:** `{colors.scrim}` rendered at 50% opacity — the global modal backdrop. Used on date pickers, login dialogs, language picker.

There are no progressive elevation tiers — the system either has the one shadow or none. Depth comes from photography, the white-on-white surface separation, and rounded-corner clipping rather than from layered shadows.

## Components

### Buttons

**`button-primary`** — Rausch fill, white text, 8px radius, 14×24px padding, 48px height, weight 500. The most common CTA across the system: "Reserve", "Continue", "Search", account-flow primaries.

**`button-primary-active`** — The press state. Background flips to `{colors.primary-active}`. No transform, no shadow change.

**`button-primary-disabled`** — Pale Rausch tint at #ffd1da with white text. Cursor not-allowed.

**`button-secondary`** — White fill with ink text and a 1px ink outline. 8px radius. Used for "Save", "Cancel", and inverse CTAs over Rausch surfaces.

**`button-tertiary-text`** — Plain ink text, no surface, no border. Underlined on hover. Used for "Show more" type links and modal close labels.

**`button-pill-rausch`** — A pill-shaped Rausch CTA used on featured cells (e.g., "Become a host" sub-CTA) — 9999px radius, 10×20px padding, 14px label.

### Search Surface

**`search-bar-pill`** — The signature global search bar. White fill, 9999px radius, 64px height, 1px hairline 1px-shadow border. Internally divided by vertical hairline rules into `{component.search-field-segment}` cells (Where / When / Who). Each segment holds an uppercase caption label above a placeholder line in `{typography.caption}`.

**`search-orb`** — The circular Rausch orb terminating the right edge of the search bar. 48×48px, fully rounded, white magnifying-glass icon centered. The hottest single color moment on the homepage.

### Top Navigation

**`top-nav`** — White surface, 80px height, 1px bottom hairline. The Airbnb wordmark sits flush left, the three product tabs (Homes / Experiences / Services) sit in the dead center, and account utilities (host link, language globe, account menu) sit flush right.

**`product-tab-active`** — Ink label in `{typography.nav-link}`, 32px hand-illustrated icon, 2px ink underline rule beneath the icon-label pair.

**`product-tab-inactive`** — Muted label, illustrated icon, no underline. Becomes active on click.

**`new-tag`** — A tiny rounded-pill badge (`{rounded.full}`) anchored top-right of an icon, carrying the uppercase "NEW" label in `{typography.uppercase-tag}` (8px / 700 with 0.32px tracking, uppercase). Used on Experiences and Services to signal recency.

### Listing Cards

**`property-card`** — A photo-first card. 1:1 aspect-ratio image with `{rounded.md}` corner clipping, image carousel dots overlay, "Guest favorite" floating badge top-left (`{component.guest-favorite-badge}`), and a heart icon top-right (`{component.icon-button-circle}` in default outlined state, Rausch-filled when saved). Beneath the image: 4–5 lines of meta — title (`{typography.title-md}`), distance / dates (`{typography.body-sm}` muted), and price ("$X night") right-aligned.

**`property-card-photo`** — The photo plate itself, separated as a token because some surfaces (wishlist, search results) reuse just the photo without the meta block.

**`experience-card`** — A taller-aspect card (4:5) for experience listings. Same `{rounded.md}` clipping, floating "NEW" badge top-left, heart top-right, and a single-line title beneath.

**`guest-favorite-badge`** — White rounded pill (`{rounded.full}`) at 11px / 600 weight. Sits over the photo with the system's only shadow tier applied for elevation.

### Listing Detail

**`rating-display-card`** — The signature listing-detail moment. A 64px / 700 rating number ("4.81") flanked left and right by tiny laurel-wreath SVG ornaments. Beneath the rating: "Guest favorite" tagline and a row of ink stat columns. The largest typographic weight in the whole system.

**`amenity-row`** — A 1-column list of amenity icons + ink labels in `{typography.body-md}`. 12px row padding, no border between rows; section is closed by a 1px hairline divider above and below.

**`reviews-card`** — A 2-column grid of review excerpts. Each column holds an author row (avatar, name, date) above a 3-line excerpt with "Show more" tertiary link.

**`host-card`** — A white card with `{rounded.md}` rounding and 24px padding holding a host avatar, name, "Superhost" badge, response-rate stat, and a "Contact host" `{component.button-secondary}`.

**`reservation-card`** — The sticky right-rail card on listing detail pages. White surface, `{rounded.md}` rounding, 1px hairline border, 1px shadow tier elevation, 24px padding. Contains: nightly price (`{typography.display-md}` ink), date-range selector, guest-count stepper, "Reserve" primary CTA full-width, and a fee breakdown stack beneath in `{typography.body-sm}`.

### Date Picker

**`date-picker-day`** — A 40×40px circular cell carrying the day number in `{typography.body-sm}`. Default state is transparent fill, ink text.

**`date-picker-day-selected`** — Ink fill, white text, full circle (`{rounded.full}`). Range states between two selected days carry a `{colors.surface-soft}` lozenge background that connects them.

### Forms

**`text-input`** — White surface, 1px hairline outline, `{rounded.sm}` 8px radius, 56px height, 14×12px padding. Stacked label above (in `{typography.caption}` muted), placeholder text in `{typography.body-md}` muted. On focus, the border thickens to 2px ink and the border color flips to `{colors.ink}` — no glow, no ring.

### Footer

**`footer-light`** — White surface (matches the page canvas — Airbnb has no contrast footer), 48×80px padding. Three columns of link blocks (Support / Hosting / Airbnb), separated by generous 24px gutters. Each column heads with a `{typography.title-sm}` ink label and stacks `{component.footer-link}` rows in `{typography.body-sm}` ink.

**`legal-band`** — A bottom strip beneath the footer columns carrying the copyright line, language picker (globe icon + "English (US)" link), currency picker, and social icons (Facebook, X, Instagram). All text in muted `{colors.muted}` at `{typography.caption-sm}`.

## Responsive Behavior

| Name    | Width       | Key Changes                                                                                                                                                                                                                               |
| ------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mobile  | < 744px     | Top nav collapses to logo + hamburger; product tabs hide behind a sheet; search bar collapses to a single tappable pill; property cards stack 1-up; city grid 1-column; listing detail collapses reservation card to a sticky bottom bar. |
| Tablet  | 744–1128px  | Top nav keeps product tabs but search bar narrows; property cards 2-up; city grid 2–3 column; reservation card stays sticky right-rail at narrower width.                                                                                 |
| Desktop | 1128–1440px | Full top nav with three product tabs centered; search bar at full pill width with all 3 segments visible; property cards 4-up; city grid 6-column; listing detail 2-column with reservation rail.                                         |
| Wide    | > 1440px    | Content width caps at 1440px on listing/search pages and ~1280px on editorial; gutters absorb the rest.                                                                                                                                   |

### Touch Targets

- Primary CTAs at minimum 48×48px (above WCAG AAA).
- Search orb is 48×48px circular — the most-tapped element on the page.
- Heart save button is 32×32px circular — borderline for AAA but compensated by a generous 12px padding inside the photo card.
- Date-picker day cells are 40×40px circular.

### Collapsing Strategy

- Top product tabs collapse into a hamburger sheet below 744px.
- Search bar's 3 segments collapse into a single-tap entry that opens a full-screen search overlay on mobile.
- Property and city-link grids drop column counts cleanly at each breakpoint — never reflow rows; always reduce columns.
- Reservation card on listing detail switches from sticky right-rail to a sticky bottom bar on mobile, carrying just the "Reserve" CTA + nightly price summary.

## Known Gaps

- **Hover state colors:** intentionally not documented per the global no-hover policy — Airbnb's actual `:hover` styling for property cards is a subtle elevation lift, but precise extraction is unreliable.
- **Loading states / skeleton screens:** not visible on the extracted surfaces.
- **Map view styling:** the search-results map uses Mapbox-tinted tiles with custom Rausch markers; not captured here.
- **Form input error states:** error text color (`{colors.primary-error-text}`) is documented, but the full input outline + helper-text combination on validation failure was not visible in the captured surfaces.
- **Sub-brand palettes:** Luxe (`{colors.luxe}`) and Plus (`{colors.plus}`) are documented as tokens, but their full sub-system (typography overrides, surface treatment) lives on separate sub-domains and is not captured here.

---

# Part Two: TripMate Extensions

**Status:** project extension, Phase 13
**Applies to:** every surface in `tripmate-web`
**Base:** everything above this line, unchanged

## Why this part exists

The spec above is Airbnb's, extracted from a photography-led consumer marketplace. It is
imported rather than authored here, and it is written for a React + Tailwind stack. TripMate
conforms to it, which is why `tripmate.css` reads as a translation.

Conforming exactly does not work for this product, for three reasons the spec itself
acknowledges:

1. Its **Known Gaps** list names loading and skeleton states, form input error states, and
   hover behaviour as undocumented. TripMate needs all three.
2. It defines **no success colour** and forbids a second accent. A notice that says an
   expense was saved therefore renders grey, indistinguishable from a notice that says
   nothing happened at all.
3. Its **one shadow tier** is a marketplace rule (photography carries the weight). In a
   dense data application, a dropdown, a dialog and a toast sharing one weight with a
   resting card makes overlays read as flat and leaves the page without hierarchy.

So this part extends the spec rather than replacing it. **Rausch remains the only brand
accent.** Nothing here introduces a second one; the additions are semantic states, depth,
motion, and a chart palette, which the base spec has no opinion about because a marketplace
listing page has no charts.

## 1. Colour: semantic states

The base spec has `{colors.primary-error-text}` and nothing else. TripMate adds four states,
each as a text/icon tone, a soft surface tint, and a border tone. These are **states, not
accents**: they never appear on a primary button, a nav item or a link.

| Token | Purpose | Value |
| --- | --- | --- |
| `--tm-color-success` | saved, settled, all clear | `#0f7b52` |
| `--tm-color-success-soft` | surface tint | `#e8f5ef` |
| `--tm-color-warning` | needs attention, not an error | `#9a6400` |
| `--tm-color-warning-soft` | surface tint | `#fdf3e2` |
| `--tm-color-danger` | destructive, failed | `#c13515` (existing `{colors.primary-error-text}`) |
| `--tm-color-danger-soft` | surface tint | `#fdeceb` |
| `--tm-color-info` | neutral notice | `#1f5fa8` |
| `--tm-color-info-soft` | surface tint | `#eaf2fc` |

Each tone clears 4.5:1 against its own soft surface and against `{colors.canvas}`, so the
tint is a background and the tone is the text, never the reverse.

**Rule:** a state colour carries meaning only alongside an icon and a label. Colour alone
never distinguishes two states, which is both an accessibility requirement and the reason
the status pills below keep their text.

## 2. Elevation: three tiers, replacing one

The base spec's single `{elevation.card}` becomes tier 1. Two more sit above it. Depth now
encodes how far a surface is from the page.

| Tier | Token | Use |
| --- | --- | --- |
| 1 | `--tm-elevation-1` | resting surfaces: cards, panels, stat tiles |
| 2 | `--tm-elevation-2` | raised: interactive card hover, sticky bars, table header on scroll |
| 3 | `--tm-elevation-3` | overlays: dropdown panels, dialogs, toasts, popovers |

Tier 3 is the only tier permitted to float above content without a border. Tiers 1 and 2 keep
a hairline, because a shadow alone is not a reliable edge at low contrast.

## 3. Gradients

The base spec has none, and its anti-pattern guidance is right that a gradient on a content
card reads as decoration. TripMate uses gradients in exactly two places, both of which are
full-bleed surfaces with no data on them:

| Token | Use |
| --- | --- |
| `--tm-gradient-brand` | the authentication shell only (sign in, register, forgot password, reset password) |
| `--tm-gradient-subtle` | empty states and the trip summary header |

`--tm-gradient-brand` is a low-angle wash from Rausch through the Plus magenta to the Luxe
purple, the three brand tones the base spec already defines. It is applied at low opacity
behind content, never as a text background, and every text tone placed on it is verified at
4.5:1 against the lightest point of the wash.

## 4. Motion

The base spec says press is a hard state flip with no transition. That holds for buttons.
Every other interactive surface gains motion, because a table row, a dropdown and a toast
that appear instantly read as a page reload.

| Token | Duration | Use |
| --- | --- | --- |
| `--tm-motion-fast` | 120ms | hover, focus, colour changes |
| `--tm-motion-base` | 180ms | dropdown and popover open, tab change, chip appear |
| `--tm-motion-slow` | 260ms | dialog and drawer open, toast enter and exit |
| `--tm-motion-ease` | `cubic-bezier(0.2, 0, 0, 1)` | the single easing curve |

**Rule:** motion conveys a spatial relationship (where a thing came from) or a state change.
Nothing animates for decoration, nothing exceeds 300ms, and no layout property (width,
height, top, left) is animated; only `opacity` and `transform`.

**Reduced motion:** under `prefers-reduced-motion: reduce`, all durations collapse to 1ms and
transforms are removed. The state change still happens, it just does not travel.

## 5. Chart palette

Charts need more than one colour, which the base spec's single-accent rule cannot supply.
Six categorical slots are defined, brand-anchored, in fixed order. The order is the
colourblind-safety mechanism: it was chosen by validating candidate orderings, not by eye.

| Slot | Hue | Value |
| --- | --- | --- |
| 1 | Rausch (brand) | `#ff385c` |
| 2 | blue | `#2a78d6` |
| 3 | green | `#12916a` |
| 4 | amber | `#c98500` |
| 5 | violet | `#4a3aa7` |
| 6 | magenta | `#d55181` |
| 7 | neutral (reserved for "Other") | `#6a6a6a` |

Validated against a `#ffffff` chart surface: lightness band, chroma floor, adjacent-pair
CVD separation (worst Delta E 8.7, target 8.0), normal-vision separation (worst 20.2, floor
15.0), and contrast (all six at or above 3:1). All checks pass with no warnings.

**Rules that follow from validation:**

- Slots are assigned **in fixed order and never cycled**. Colour follows the entity, so
  filtering a chart never repaints the categories that remain.
- A seventh category is never a generated hue. Categories past the sixth fold into **Other**
  in the neutral slot. This matters here because expense category is free text and the seed
  data already uses seven.
- Slot 7 is reserved and never used as a series colour.
- Every chart with two or more series carries a legend, and every chart is paired with a
  table of the same figures, so identity is never carried by colour alone. This is also the
  relief the contrast check requires.

## 6. Responsive behaviour for an application

The base spec's breakpoints describe a marketplace: cards that stack, a search pill that
collapses. TripMate is a data application, so the interesting question at narrow widths is
what happens to a table.

| Name | Width | Key changes |
| --- | --- | --- |
| Mobile | under 744px | top nav collapses to a logo plus a menu button opening an off-canvas panel; tables become stacked cards, one card per row, each labelled by its column headers; multi-column forms become one column; charts drop to a single column and reduce their label density |
| Tablet | 744 to 1023px | nav shows links but not account actions; tables keep their columns and scroll horizontally inside a wrapper rather than breaking the page; forms use two columns |
| Desktop | 1024 to 1439px | full nav; two-column page layouts; charts side by side where they compare |
| Wide | 1440px and above | content caps at `--tm-container-wide`; gutters absorb the rest |

Cross-cutting rules:

- No horizontal page scroll at any width. A wide table scrolls inside its own wrapper.
- Every interactive target is at least 44 by 44 pixels.
- Space is reserved for anything that loads asynchronously, so nothing shifts after paint.
  Charts declare their height, images declare an aspect ratio, and a region that is loading
  renders a skeleton at its final size.

## 7. What this part does not change

- Rausch is still the only brand accent. The semantic states are not accents.
- Typography, radii and the spacing scale are unchanged.
- The `body`-prefixed PrimeFaces override idiom in `tripmate.css` is load-bearing and stays.
- No dark mode. Tokens are structured so one could be added later, but nothing here defines
  dark values and no surface is written against a dark assumption.
