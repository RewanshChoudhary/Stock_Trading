---
version: "alpha"
name: "Ukiyo-e Woodblock Revival"
description: "Ukiyo-e landing page, woodblock print style, japanese art, bold outlines, prussian blue, paper texture, traditional graphic design. Ideal for landing pages, modern websites. AI-ready template."
colors:
  primary: "#F0E3CE"
  secondary: "#0D0D15"
  tertiary: "#E85D35"
  neutral: "#2A4056"
  surface: "#003153"
  accent: "#CC7722"
typography:
  h1:
    fontFamily: Cinzel
    fontSize: 2.5rem
    fontWeight: 700
  body-md:
    fontFamily: Cinzel
    fontSize: 1rem
    fontWeight: 400
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.neutral}"
    padding: 12px
---

## Overview

Ukiyo-e landing page, woodblock print style, japanese art, bold outlines, prussian blue, paper texture, traditional graphic design. Ideal for landing pages, modern websites. AI-ready template. Hokusai didn't care about realism. Neither did Hiroshige. What they cared about was essence — distilling a mountain, a wave, a bridge in rain down to its most potent visual form. Flat planes of color. Decisive outlines. Zero apology.

This is why ukiyo-e translates so cleanly to screens. The woodblock process itself enforced constraints that digital designers chase voluntarily: limited color palettes, hard edges, no gradients to hide behind. Every shape had to earn its place on the block. The Great Wave isn't just an art history footnote — it's a masterclass in visual hierarchy that still outperforms most modern compositions. One focal point. Layered depth through overlap, not shadow. Color doing structural work.

When Art Nouveau hit Europe, it was ukiyo-e they were stealing from. When flat design emerged in 2012, it was rediscovering principles that Edo-period printmakers solved centuries earlier. The revival isn't nostalgia. It's recognition that these constraints produce clarity — and clarity is what interfaces desperately need.

- Density: 5/10 — Balanced
- Variance: 4/10 — Moderate
- Motion: 4/10 — Subtle

- **Style:** Historical, Dramatic, Bold
- **Keywords:** ukiyo-e, woodblock, japanese, great wave, bold, outlines, vintage, texture
- **Era:** Edo Period
- **Light/Dark:** ✓ Full / ✗ No

## Colors

- **Background** (#F0E3CE) — Primary background surface
- **Text** (#0D0D15) — Primary text color
- **Accent** (#E85D35) — Primary accent, CTAs and interactive elements
- **Indigo** (#2A4056) — Accent color, emphasis elements
- **Prussian Blue** (#003153) — Secondary accent
- **Ochre** (#CC7722) — Extended palette, decorative use


## Typography

- **Display / Hero:** Cinzel — Weight 700, tight tracking, used for headline impact
- **Body:** Cinzel — Weight 400, 16px/1.6 line-height, max 72ch per line
- **UI Labels / Captions:** Cinzel — 0.875rem, weight 500, slight letter-spacing
- **Monospace:** JetBrains Mono — Used for code, metadata, and technical values

Scale:
- Hero: clamp(2.5rem, 5vw, 4rem)
- H1: 2.25rem
- H2: 1.5rem
- Body: 1rem / 1.6
- Small: 0.875rem


## Layout

- **Grid:** CSS Grid primary. Max-width containment: 1280px centered with 1.5rem side padding.
- **Spacing rhythm:** Balanced. Base unit: 0.5rem (8px).
- **Section vertical gaps:** clamp(4rem, 8vw, 8rem).
- **Hero layout:** Split-screen (text left, visual right).
- **Feature sections:** Zig-zag alternating text+image rows. No 3-equal-columns.
- **Mobile collapse:** All multi-column layouts collapse below 768px. No horizontal overflow.
- **z-index contract:** base (0) / sticky-nav (100) / overlay (200) / modal (300) / toast (500).


## Elevation & Depth

Traditional woodblock aesthetics, bold brush outlines, comic-strip paneling, aged washi paper grain, high contrast color blocking.

- **Physics:** Ease-out curves, 200-300ms duration. Smooth and predictable.
- **Entry animations:** Fade + translate-Y (16px → 0) over 420ms ease-out. Staggered cascades for lists: 80ms between items.
- **Hover states:** Subtle color shift + shadow adjustment over 200ms.
- **Page transitions:** Fade only (200ms).
- **Performance:** Only transform and opacity animated. No layout-triggering properties.


## Shapes

Base corner radius: 8px. See rounded tokens in front matter for the full scale.


## Components

- **Primary Button:** Subtly rounded (0.5rem) shape. Accent color fill. Hover: 8% darken + subtle lift shadow. Active: -1px translate tactile press. Font weight 600. No outer glows.
- **Secondary / Ghost Button:** Outline variant. 1.5px border in muted color. Text in primary color. Hover: subtle background fill.
- **Cards:** Subtly rounded (0.5rem) corners. Surface background. Subtle shadow (0 2px 12px rgba(0,0,0,0.06)). 1px border stroke.
- **Inputs:** Label above input. 1px border stroke. Focus ring: 2px accent color offset 2px. Error text below in semantic red. No floating labels.
- **Navigation:** Primary surface background. Active item: accent color indicator. Font weight 500 when active.
- **Skeletons:** Shimmer animation matching component dimensions. No circular spinners.
- **Empty States:** Icon-based composition with descriptive text and action button.


## Do's and Don'ts

- No emojis in UI — use icon system only (Lucide, Heroicons)
- No pure black (#000000) — use off-black or charcoal variants
- No oversaturated accent colors (saturation cap: 80%)
- No 3-column equal-width feature layouts — use zig-zag or asymmetric grid
- No `h-screen` — use `min-h-[100dvh]`
- No AI copywriting clichés: "Elevate", "Seamless", "Unleash", "Next-Gen"
- No broken external image links — use picsum.photos or inline SVG
- No generic lorem ipsum in demos

- Do Washi paper background
- Do Thick bold outlines
- Do Flat high-contrast colors
- Do Woodblock print textures
- Do Panel-based layout


## Use Case

Landing pages, Modern websites
