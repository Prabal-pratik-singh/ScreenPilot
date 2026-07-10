import clsx from 'clsx'

/** ScreenPilot wordmark: marigold screen-with-play glyph + word. `dark` renders for navy backgrounds. */
export function Logo({ dark = false, size = 'md', className }) {
  const text = dark ? 'text-white' : 'text-ink-800'
  const sizes = { sm: 'text-base', md: 'text-lg', lg: 'text-3xl' }
  const glyph = { sm: 22, md: 26, lg: 44 }[size]
  return (
    <div className={clsx('flex items-center gap-2 select-none', className)}>
      <svg width={glyph} height={glyph} viewBox="0 0 48 48" fill="none" aria-hidden="true">
        <rect x="2" y="2" width="44" height="44" rx="12" fill="#F6A821" />
        <rect x="10" y="12" width="28" height="19" rx="3" fill="#16233F" />
        <polygon points="21,16.5 21,26.5 29.5,21.5" fill="#F6A821" />
        <rect x="21.5" y="31" width="5" height="3.5" fill="#16233F" />
        <rect x="17" y="34.5" width="14" height="2.8" rx="1.4" fill="#16233F" />
      </svg>
      <span className={clsx('font-bold tracking-tight', text, sizes[size])}>
        screen<span className="text-marigold">Pilot</span>
      </span>
    </div>
  )
}
