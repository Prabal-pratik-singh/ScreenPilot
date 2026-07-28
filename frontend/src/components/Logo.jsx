// Brand lockup: a rounded-square monitor mark stroked with the primary
// violet -> fuchsia gradient, next to the wordmark ("Screen" in white,
// "Pilot" filled with the logo gradient) and the letter-spaced tagline.
// All text comes from src/config/brand.js so the brand stays swappable.
import clsx from 'clsx'
import { BRAND } from '../config/brand'

// The monitor glyph as an inline SVG; `size` is the box in px.
export function LogoMark({ size = 34, className }) {
  return (
    <svg width={size} height={size} viewBox="0 0 34 34" fill="none" className={className} aria-hidden="true">
      <defs>
        <linearGradient id="sp-grad" x1="0" y1="0" x2="34" y2="34" gradientUnits="userSpaceOnUse">
          <stop stopColor="#A78BFA" />
          <stop offset="1" stopColor="#E879F9" />
        </linearGradient>
      </defs>
      {/* rounded-square monitor body */}
      <rect x="3.5" y="5.5" width="27" height="18" rx="4.5" stroke="url(#sp-grad)" strokeWidth="2.4" />
      {/* play triangle on the screen */}
      <path d="M14.4 11.6v6.8l6-3.4-6-3.4Z" fill="url(#sp-grad)" />
      {/* stand */}
      <path d="M13 28.5h8M17 24v4" stroke="url(#sp-grad)" strokeWidth="2.4" strokeLinecap="round" />
    </svg>
  )
}

// Full lockup: mark + split-color wordmark + optional tagline underneath.
export function Logo({ size = 'md', withTagline = false, className }) {
  const textSizes = { sm: 'text-base', md: 'text-lg', lg: 'text-3xl' }
  const markSizes = { sm: 24, md: 34, lg: 46 }
  // wordmark splits at the first capital after position 0: "Screen" | "Pilot"
  const splitAt = BRAND.name.slice(1).search(/[A-Z]/) + 1
  const head = splitAt > 0 ? BRAND.name.slice(0, splitAt) : BRAND.name
  const tail = splitAt > 0 ? BRAND.name.slice(splitAt) : ''
  return (
    <div className={clsx('flex items-center gap-2.5 select-none', className)}>
      <LogoMark size={markSizes[size]} />
      <div className="leading-none">
        <span className={clsx('font-bold tracking-tight', textSizes[size])}>
          <span className="text-txt-primary">{head}</span>
          <span className="text-grad-logo">{tail}</span>
        </span>
        {withTagline && (
          <p className="text-[10px] tracking-[0.28em] text-txt-muted mt-1 uppercase">{BRAND.tagline}</p>
        )}
      </div>
    </div>
  )
}
