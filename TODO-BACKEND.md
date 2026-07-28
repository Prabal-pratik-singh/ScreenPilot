# TODO-BACKEND — endpoints the new dashboard design wants but the API lacks

The dark ScreenPilot frontend renders gracefully without these (zeros/placeholders),
but each item below would let the UI show real data instead.

## 1. Historical deltas for KPI cards
The four stat cards show a `↑ 0%` delta pill as a placeholder. There is no endpoint
returning period-over-period comparisons.
- Wanted: `GET /api/dashboard/stats?compare=7d` → `{ total, online, offline,
  offlineOver24h, deltas: { totalPct, onlinePct, ... } }` (deltas vs the previous
  period). Requires persisting daily snapshots of the counts.

## 2. Dedicated network-activity time series
The "Network activity" chart currently reuses `GET /api/reports/proof-of-play`
(playsPerDay) and `GET /api/reports/uptime` for the side stats, which means two
report-sized queries on every dashboard load.
- Wanted: `GET /api/dashboard/activity?days=7` → `{ series: [{date, plays}],
  uptimePct, totalPlays, activeSchedules }` — one cheap, cacheable call.

## 3. Weather proxy
The sidebar widget calls Open-Meteo directly from the browser
(`api.open-meteo.com`) with a 15-minute client cache. Fine for now, but a backend
proxy would centralize caching, hide the third-party dependency and let the
location come from configuration instead of hard-coded Ranchi coordinates.
- Wanted: `GET /api/dashboard/weather` → `{ tempC, condition, location }`.

## 4. Per-node "stale" counts in the locations tree
The tree endpoint (`GET /api/dashboard/tree`) returns `online`/`offline` per node.
The design's amber dot means "stale / offline > 24h", which cannot be derived
client-side from the current payload.
- Wanted: add `stale` (offline > 24h count) to each tree node.

## 5. Notifications
The topbar bell is decorative — there is no notifications API.
- Wanted: `GET /api/notifications` (+ mark-read) fed by events like screen
  offline > X min, failed downloads, expiring schedules.
