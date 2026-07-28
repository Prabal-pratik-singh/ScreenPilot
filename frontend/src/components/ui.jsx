// Shared UI building blocks used by every portal page: Card, PageHeader,
// StatusDot, Badge, Modal, Spinner, Skeleton, EmptyState, Field and
// ConfirmDialog. All styling is Tailwind utility classes; there is no
// component library underneath.
import { useEffect } from 'react'
import clsx from 'clsx'
import { X, Inbox } from 'lucide-react'

// White rounded panel — the basic container for page content.
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
        <h1 className="text-2xl font-bold text-ink-800">{title}</h1>
        {subtitle && <p className="text-sm text-ink-400 mt-1">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}

/** green = online, red = offline, amber = warning/degraded */
export function StatusDot({ status, className, pulse = false }) {
  const color =
    status === 'ONLINE' ? 'bg-success' : status === 'WARNING' ? 'bg-warning' : 'bg-danger'
  return (
    <span className={clsx('relative inline-flex h-2.5 w-2.5 rounded-full', color, className)}>
      {pulse && status === 'ONLINE' && (
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-success opacity-60" />
      )}
    </span>
  )
}

// Small pill label; `tone` picks the color scheme (success, danger, ...).
export function Badge({ children, tone = 'ink', className }) {
  const tones = {
    ink: 'bg-ink-50 text-ink-600',
    marigold: 'bg-marigold-100 text-marigold-800',
    success: 'bg-success-100 text-success-700',
    danger: 'bg-danger-100 text-danger-700',
    warning: 'bg-warning-100 text-warning-700',
  }
  return (
    <span className={clsx('inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold', tones[tone], className)}>
      {children}
    </span>
  )
}

// Centered dialog with a dimmed backdrop. Closes on Escape or backdrop click.
export function Modal({ open, onClose, title, children, wide = false }) {
  useEffect(() => {
    // Listen for Escape only while the modal is open.
    const onKey = (e) => e.key === 'Escape' && onClose?.()
    if (open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink-900/50 backdrop-blur-[2px]" onClick={onClose} />
      <div className={clsx('relative card w-full max-h-[90vh] overflow-y-auto p-6', wide ? 'max-w-3xl' : 'max-w-lg')}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-ink-800">{title}</h2>
          <button onClick={onClose} className="text-ink-300 hover:text-ink-600 rounded-lg p-1">
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
    <div className={clsx('inline-block h-5 w-5 animate-spin rounded-full border-2 border-ink-200 border-t-marigold', className)} />
  )
}

// Grey pulsing placeholder shown while data is loading.
export function Skeleton({ className }) {
  return <div className={clsx('animate-pulse rounded-lg bg-ink-100/70', className)} />
}

// Friendly "nothing here yet" block with icon, hint text and optional CTA.
export function EmptyState({ icon: Icon = Inbox, title, hint, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-14 text-center">
      <div className="rounded-2xl bg-ink-50 p-4 mb-3">
        <Icon size={28} className="text-ink-300" />
      </div>
      <p className="font-semibold text-ink-600">{title}</p>
      {hint && <p className="text-sm text-ink-400 mt-1 max-w-sm">{hint}</p>}
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
      {hint && <p className="text-xs text-ink-300 mt-1">{hint}</p>}
    </div>
  )
}

// Yes/no confirmation modal for destructive actions; `busy` shows a spinner
// on the confirm button while the mutation is running.
export function ConfirmDialog({ open, onClose, onConfirm, title, message, confirmLabel = 'Delete', busy = false, children }) {
  return (
    <Modal open={open} onClose={onClose} title={title}>
      <div className="text-sm text-ink-600">{message}</div>
      {children}
      <div className="mt-6 flex justify-end gap-2">
        <button className="btn-ghost" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button className="btn-danger" onClick={onConfirm} disabled={busy}>
          {busy ? <Spinner className="h-4 w-4 border-white/40 border-t-white" /> : confirmLabel}
        </button>
      </div>
    </Modal>
  )
}
