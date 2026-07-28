// Single source of truth for visible branding. Every place that shows the
// product name (sidebar logo, document title, login) reads from here so the
// brand can be swapped without hunting through components.
export const BRAND = {
  name: 'ScreenPilot', // rendered as "Screen" (white) + "Pilot" (gradient)
  tagline: 'DIGITAL SIGNAGE', // letter-spaced small caps under the name
}
