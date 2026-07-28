// Live updates for the portal via STOMP over WebSocket (a simple pub/sub
// messaging protocol running on top of a WebSocket connection).
// The backend broadcasts screen online/offline changes on a topic; this hook
// listens and patches the TanStack Query cache directly, so lists update
// instantly without any HTTP refetch.
import { useEffect, useRef } from 'react'
import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { WS_URL } from '../api/client'

/**
 * Subscribes to the live screen status stream and keeps every cached
 * screen list / screen detail / dashboard query up to date without refetching.
 */
// `enabled` lets a caller keep the socket off (e.g. before login) while still
// calling the hook unconditionally, as the rules of hooks require.
export function usePortalSocket(enabled = true) {
  // The same TanStack Query cache created in main.jsx — writing into it here
  // instantly re-renders every component that displays those queries.
  const queryClient = useQueryClient()
  // Keeps the STOMP client across renders without causing re-renders itself.
  const clientRef = useRef(null)

  // Effect watches `enabled` (queryClient is stable): open the socket when it
  // becomes true, tear it down when it flips false or the component unmounts.
  useEffect(() => {
    // Disabled → no socket. Returning undefined means "no cleanup to run".
    if (!enabled) return undefined
    // STOMP client aimed at the backend's /ws endpoint. reconnectDelay makes it
    // self-healing: if the connection drops (server restart, network blip) it
    // quietly redials every 4 seconds until it is back on.
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 4000,
    })
    // Subscribing happens inside onConnect so the subscription is re-created
    // automatically after every reconnect — not just on the first connection.
    client.onConnect = () => {
      // One topic carries every screen event; each message is a JSON envelope
      // with a "type" field telling us what changed.
      client.subscribe('/topic/portal/screens', (message) => {
        let event
        try {
          event = JSON.parse(message.body)
        } catch {
          // Malformed frame — ignore it rather than let one bad message crash
          // the handler and stop all future updates.
          return
        }
        if (event.type === 'SCREEN_UPDATED' && event.screen) {
          const screen = event.screen
          // Replace (or append) the screen inside every cached ['screens'] list.
          // setQueriesData (plural) prefix-matches EVERY cached query whose key
          // starts with ['screens'] — including filtered/paged variants — and
          // patches each one in place. Patching beats refetching here: screens
          // send heartbeats constantly, and re-downloading every list for each
          // heartbeat would hammer the API for data this event already contains.
          queryClient.setQueriesData({ queryKey: ['screens'] }, (old) => {
            // Leave anything that isn't a loaded list (undefined, odd shapes) alone.
            if (!Array.isArray(old)) return old
            const idx = old.findIndex((s) => s.id === screen.id)
            // Unknown id = screen paired after this list was fetched — append it.
            if (idx === -1) return [...old, screen]
            // Copy-then-replace keeps the update immutable: the NEW array
            // identity is what tells React Query and React that data changed.
            const next = [...old]
            next[idx] = screen
            return next
          })
          // The single-screen detail page cache gets the fresh object too.
          queryClient.setQueriesData({ queryKey: ['screen', screen.id] }, () => screen)
          // Dashboard numbers (online counts, aggregates) are computed on the
          // server — one screen event isn't enough to recalculate them locally,
          // so mark them stale and let React Query refetch instead of patching.
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        }
        // A deleted screen is filtered out of the cached lists.
        if (event.type === 'SCREEN_REMOVED') {
          // Same prefix-match trick: drop the id from every cached screens list.
          queryClient.setQueriesData({ queryKey: ['screens'] }, (old) =>
            Array.isArray(old) ? old.filter((s) => s.id !== event.screenId) : old,
          )
          // Counts changed, so the dashboard refetches — same reason as above.
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        }
      })
    }
    // Open the connection (this also starts the auto-reconnect loop).
    client.activate()
    clientRef.current = client
    // Close the socket cleanly when the component using the hook unmounts.
    // deactivate() also cancels the reconnect timer, so a closed portal tab or
    // a logged-out user doesn't keep redialing the server in the background.
    return () => {
      client.deactivate()
      clientRef.current = null
    }
  }, [enabled, queryClient])
}
