// Shared UI building blocks used by every portal page: Card, PageHeader,
// SectionHeader, StatusDot, IconTile, Badge, Modal, Spinner, Skeleton,
// EmptyState, Field and ConfirmDialog — all styled with the dark ScreenPilot
// tokens (bg-card surfaces, subtle borders, violet gradient accents).
import { useEffect } from 'react'
import clsx from 'clsx'
import { X, Inbox } from 'lucide-react'

// Dark rounded panel — the basic container for page content.
export function Card({ className, children, ...props }) {
  return (
    <div className={clsx('card', className)} {...props}>
      {children}
    </div>
  )
}

// Page title row with optional subtitle and action buttons on the right.
export function PageHeader({ title, subtitle, actions }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
      <div>
        <h1 className="text-[22px] font-semibold text-txt-primary tracking-tight">{title}</h1>
        {subtitle && <p className="text-sm text-txt-secondary mt-1">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}

// Card-level header: 15px semibold title + optional right-side element.
export function SectionHeader({ title, aside, className }) {
  return (
    <div className={clsx('flex items-center justify-between mb-4', className)}>
      <h2 className="text-[15px] font-semibold text-txt-primary">{title}</h2>
      {aside}
    </div>
  )
}

// Rounded-12 tinted icon container (the "icon tile" from the design system).
// tone: primary | success | danger | warning | info
export function IconTile({ icon: Icon, tone = 'primary', size = 44, iconSize = 20, className }) {
  const tones = {
    primary: 'bg-primary-500/15 text-primary-400',
    success: 'bg-success/15 text-success-400',
    danger: 'bg-danger/15 text-danger',
    warning: 'bg-warning/15 text-warning',
    info: 'bg-info/15 text-info',
  }
  return (
    <div
      className={clsx('rounded-tile flex items-center justify-center shrink-0', tones[tone], className)}
      style={{ width: size, height: size }}
    >
      <Icon size={iconSize} />
    </div>
  )
}

/** green = online, rose = offline, amber = warning/stale — with a soft glow */
export function StatusDot({ status, className, pulse = false }) {
  const color =
    status === 'ONLINE'
      ? 'bg-success-400 shadow-glow-success'
      : status === 'WARNING'
        ? 'bg-warning'
        : 'bg-danger shadow-glow-danger'
  return (
    <span className={clsx('relative inline-flex h-2.5 w-2.5 rounded-full', color, className)}>
      {pulse && status === 'ONLINE' && (
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-success-400 opacity-60 motion-reduce:hidden" />
      )}
    </span>
  )
}

// Small pill label; `tone` picks the tinted color scheme.
export function Badge({ children, tone = 'ink', className }) {
  const tones = {
    ink: 'bg-white/[0.06] text-txt-secondary',
    marigold: 'bg-primary-500/15 text-primary-400', // legacy tone name -> brand tint
    primary: 'bg-primary-500/15 text-primary-400',
    success: 'bg-success/15 text-success-400',
    danger: 'bg-danger/15 text-danger',
    warning: 'bg-warning/15 text-warning',
    info: 'bg-info/15 text-info',
  }
  return (
    <span className={clsx('inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium', tones[tone], className)}>
      {children}
    </span>
  )
}

// Centered dialog with a blurred dark backdrop. Closes on Escape or backdrop click.
export function Modal({ open, onClose, title, children, wide = false }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.()
    if (open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />
      <div className={clsx('relative card animate-pop-in w-full max-h-[90vh] overflow-y-auto p-6', wide ? 'max-w-3xl' : 'max-w-lg')}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-txt-primary">{title}</h2>
          <button onClick={onClose} className="text-txt-muted hover:text-txt-primary rounded-lg p-1 transition-colors">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

export function Spinner({ className }) {
  return (
    <div className={clsx('inline-block h-5 w-5 animate-spin rounded-full border-2 border-white/15 border-t-primary-400', className)} />
  )
}

// Loading placeholder with a moving shimmer highlight on the inner surface.
export function Skeleton({ className }) {
  return (
    <div
      className={clsx(
        'animate-shimmer rounded-tile bg-card-inner bg-gradient-to-r from-white/[0.02] via-white/[0.07] to-white/[0.02] bg-[length:800px_100%] border border-subtle',
        className,
      )}
    />
  )
}

// Friendly "nothing here yet" block: tinted icon tile + explanation + CTA.
export function EmptyState({ icon: Icon = Inbox, title, hint, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-14 text-center">
      <div className="rounded-card bg-primary-500/10 ring-1 ring-primary-500/20 p-4 mb-3">
        <Icon size={28} className="text-primary-400" />
      </div>
      <p className="font-medium text-txt-primary">{title}</p>
      {hint && <p className="text-sm text-txt-secondary mt-1 max-w-sm">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

// Form row: label above the input, optional hint below.
export function Field({ label, children, hint }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <p className="text-xs text-txt-muted mt-1">{hint}</p>}
    </div>
  )
}

// Yes/no confirmation modal for destructive actions; `busy` shows a spinner
// on the confirm button while the mutation is running.
export function ConfirmDialog({ open, onClose, onConfirm, title, message, confirmLabel = 'Delete', busy = false, children }) {
  return (
    <Modal open={open} onClose={onClose} title={title}>
      <div className="text-sm text-txt-secondary">{message}</div>
      {children}
      <div className="mt-6 flex justify-end gap-2">
        <button className="btn-ghost" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button className="btn-danger" onClick={onConfirm} disabled={busy}>
          {busy ? <Spinner className="h-4 w-4 border-danger/40 border-t-danger" /> : confirmLabel}
        </button>
      </div>
    </Modal>
  )
}
