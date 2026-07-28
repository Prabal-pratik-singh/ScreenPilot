// Settings page (ADMIN and up): lists the pluggable content-source
// integrations reported by the backend (Canva, Google Drive, ...) plus
// static cards describing storage and timezone behavior. Read-only for now —
// the Connect buttons stay disabled until server credentials exist.
import { useQuery } from '@tanstack/react-query'
import { Plug, Link2, HardDrive, Clock } from 'lucide-react'
import { api } from '../api/client'
import { Card, PageHeader, Skeleton, Badge } from '../components/ui'

const PROVIDER_ICON = {
  canva: '🎨',
  'google-drive': '📁',
  onedrive: '☁️',
  'power-bi': '📊',
}

export default function SettingsPage() {
  const integrations = useQuery({ queryKey: ['integrations'], queryFn: () => api.get('/integrations').then((r) => r.data) })

  return (
    <div>
      <PageHeader title="Settings" subtitle="Platform configuration and content-source integrations" />

      <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
        <Plug size={17} className="text-marigold-600" /> Content sources
      </h2>
      <p className="text-sm text-ink-400 mb-4 max-w-2xl">
        External platforms plug in through the <code className="bg-ink-50 px-1.5 py-0.5 rounded text-ink-700">ContentSourceProvider</code> interface.
        Each becomes available once API credentials are configured on the server — no credentials are bundled with this build.
      </p>

      {integrations.isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {[...Array(4)].map((_, i) => <Skeleton key={i} className="h-36 w-full" />)}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
          {(integrations.data || []).map((p) => (
            <Card key={p.id} className="p-5">
              <div className="flex items-start justify-between">
                <span className="text-3xl">{PROVIDER_ICON[p.id] || '🔌'}</span>
                {p.enabled ? <Badge tone="success">Connected</Badge> : <Badge>Not connected</Badge>}
              </div>
              <h3 className="font-bold text-ink-800 mt-3">{p.displayName}</h3>
              <p className="text-sm text-ink-400 mt-1">{p.description}</p>
              <div className="mt-4 flex items-center justify-between gap-3">
                <p className="text-[11px] text-ink-300 flex-1">{p.requirement}</p>
                <button className="btn-ghost" disabled title={p.requirement}>
                  <Link2 size={14} /> Connect
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
        <HardDrive size={17} className="text-marigold-600" /> Platform
      </h2>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <Card className="p-5">
          <h3 className="font-bold text-ink-800">Storage</h3>
          <p className="text-sm text-ink-400 mt-1">
            Media is stored on local disk under <code className="bg-ink-50 px-1.5 py-0.5 rounded text-ink-700">./uploads</code> behind
            the <code className="bg-ink-50 px-1.5 py-0.5 rounded text-ink-700">StorageService</code> interface, so S3 can be swapped in
            without touching upload, thumbnail or streaming code. Max upload: 500 MB per file.
          </p>
        </Card>
        <Card className="p-5">
          <h3 className="font-bold text-ink-800 flex items-center gap-2"><Clock size={15} className="text-ink-300" /> Timezone</h3>
          <p className="text-sm text-ink-400 mt-1">
            All scheduling runs in <b>IST (Asia/Kolkata)</b>. Timestamps are stored in UTC and converted at the edges —
            server timezone never affects what plays.
          </p>
        </Card>
      </div>
    </div>
  )
}
