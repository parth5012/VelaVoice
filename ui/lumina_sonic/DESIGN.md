---
name: Lumina Sonic
colors:
  surface: '#0e1514'
  surface-dim: '#0e1514'
  surface-bright: '#333b3a'
  surface-container-lowest: '#090f0f'
  surface-container-low: '#161d1c'
  surface-container: '#1a2120'
  surface-container-high: '#242b2a'
  surface-container-highest: '#2f3635'
  on-surface: '#dde4e2'
  on-surface-variant: '#bacac7'
  inverse-surface: '#dde4e2'
  inverse-on-surface: '#2b3231'
  outline: '#859491'
  outline-variant: '#3c4948'
  surface-tint: '#3cdcd1'
  primary: '#ffffff'
  on-primary: '#003734'
  primary-container: '#62f9ee'
  on-primary-container: '#00716b'
  inverse-primary: '#006a64'
  secondary: '#ddb7ff'
  on-secondary: '#4a0080'
  secondary-container: '#622599'
  on-secondary-container: '#d1a1ff'
  tertiary: '#ffffff'
  on-tertiary: '#393000'
  tertiary-container: '#fce363'
  on-tertiary-container: '#736400'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#62f9ee'
  primary-fixed-dim: '#3cdcd1'
  on-primary-fixed: '#00201e'
  on-primary-fixed-variant: '#00504b'
  secondary-fixed: '#f0dbff'
  secondary-fixed-dim: '#ddb7ff'
  on-secondary-fixed: '#2c0050'
  on-secondary-fixed-variant: '#622599'
  tertiary-fixed: '#fce363'
  tertiary-fixed-dim: '#dec74a'
  on-tertiary-fixed: '#211b00'
  on-tertiary-fixed-variant: '#524700'
  background: '#0e1514'
  on-background: '#dde4e2'
  surface-variant: '#2f3635'
typography:
  display-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 40px
    fontWeight: '700'
    lineHeight: 48px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  title-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.01em
  mono-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  container-margin: 20px
  gutter: 16px
---

## Brand & Style

The design system is engineered for a premium, high-utility transcription experience that feels both technologically advanced and intuitively physical. The brand personality is "Ethereal Precision"—it combines the cold, sharp efficiency of AI with a soft, glowing tactile interface that responds to the human voice.

The visual style is a hybrid of **OLED-first Minimalism** and **Glassmorphism**. By utilizing a true-black base, the interface maximizes battery efficiency on mobile devices while allowing vibrant neon accents to "pop" with an emissive quality. Surface depth is communicated through light-refracting glass layers rather than traditional shadows, creating a sense of weightless technology that feels "magical" yet grounded.

## Colors

This design system utilizes a high-contrast dark palette designed for clarity and focus.

- **Primary (Neon Cyan):** Reserved for active voice capture, waveform visualizations, and primary action buttons. It represents the "live" state of the app.
- **Secondary (Electric Indigo):** Used for AI-driven processes, such as text cleanup, summarization, and background task indicators.
- **Base (Obsidian to Slate):** The background is deep obsidian to ensure seamless blending with OLED hardware. Surface layers use slate to provide subtle structural definition.
- **Glass Layers:** A semi-transparent white overlay at 8% opacity creates the glass effect. This should always be paired with a 24px backdrop blur to maintain legibility over moving waveforms or background content.

## Typography

Typography is used to create a clear hierarchy between recorded content and interface controls. 

- **Plus Jakarta Sans** is used for headlines and titles to provide a modern, friendly, and geometric feel.
- **Inter** is used for body text and transcripts to maximize legibility. 
- **Transcript Text:** Use `body-lg` for the active transcription view to ensure comfortable reading during playback.
- **Emissive Text:** For critical status updates (e.g., "RECORDING"), use `mono-sm` in all-caps with the primary accent color and a subtle outer glow.

## Layout & Spacing

The design system follows a 12-column fluid grid for tablet/desktop and a 4-column fluid grid for mobile. 

- **Safe Zones:** Always maintain a 20px margin from the screen edges on mobile.
- **Rhythm:** Use an 8px base grid. All padding and margins should be multiples of 8 (or 4 for tight internal component spacing).
- **Floating Controls:** Action buttons (like the Record button) should be positioned using fixed bottom-center anchors with a 32px offset from the bottom navigation or screen edge.

## Elevation & Depth

Depth is not communicated through shadows, but through **translucency and stroke.**

1.  **Level 0 (Background):** Solid #0B0C10.
2.  **Level 1 (Cards/Items):** Solid #1F2833 or a very subtle gradient.
3.  **Level 2 (Floating Elements):** Glassmorphic surfaces (8% white + 24px blur). These must have a 1px inner border (top/left 15% white, bottom/right 5% white) to simulate the edge of a glass pane.
4.  **Active State:** Elements like the active recording card should have a subtle outer "aura" or glow using the Primary color (15% opacity) to indicate they are emitting sound/data.

## Shapes

The shape language is rounded and organic, reflecting the fluid nature of sound.

- **Standard Containers:** 16px (rounded-lg) corner radius for most cards and sheets.
- **Buttons:** 24px+ or fully pill-shaped to distinguish interactive elements from content containers.
- **Interactive Feedback:** When pressed, elements should subtly scale down (98%) to provide tactile physical feedback.

## Components

### Buttons
- **Primary Action (Record):** A large circular button with a Primary color gradient. When active, it should pulse with a Primary color glow.
- **Secondary Action:** Glassmorphic background with white text.

### Cards & Lists
- **Transcript Snippets:** Use Level 1 surface with 16px corner radius.
- **Active Transcription:** Use a Glassmorphic surface to float above the background, allowing the waveform to be seen moving underneath.

### Input Fields
- Transparent background with a 1px Slate border. Upon focus, the border transitions to Primary Cyan with a subtle outer glow.

### Waveforms
- Use a series of rounded vertical bars. The bars should animate in height based on audio input, using the Primary color for live audio and Secondary for processed AI audio.

### Bottom Sheets
- All modal sheets must use the Glassmorphic treatment with a "drag handle" at the top center. The background behind the sheet should dim slightly (40% black).

### Chips
- Used for "AI Tags" or "Keywords". Use a 20% Secondary (Indigo) fill with a Secondary solid stroke and white text.