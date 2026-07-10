import { useEffect, useRef } from 'react'
import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { WS_URL } from '../api/client'

/**
 * Subscribes to the live screen status stream and keeps every cached
 * screen list / screen detail / dashboard query up to date without refetching.
 */
export function usePortalSocket(enabled = true) {
  const queryClient = useQueryClient()
  const clientRef = useRef(null)

  useEffect(() => {
    if (!enabled) return undefined
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 4000,
    })
    client.onConnect = () => {
      client.subscribe('/topic/portal/screens', (message) => {
        let event
        try {
          event = JSON.parse(message.body)
        } catch {
          return
        }
        if (event.type === 'SCREEN_UPDATED' && event.screen) {
          const screen = event.screen
          queryClient.setQueriesData({ queryKey: ['screens'] }, (old) => {
            if (!Array.isArray(old)) return old
            const idx = old.findIndex((s) => s.id === screen.id)
            if (idx === -1) return [...old, screen]
            const next = [...old]
            next[idx] = screen
            return next
          })
          queryClient.setQueriesData({ queryKey: ['screen', screen.id] }, () => screen)
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        }
        if (event.type === 'SCREEN_REMOVED') {
          queryClient.setQueriesData({ queryKey: ['screens'] }, (old) =>
            Array.isArray(old) ? old.filter((s) => s.id !== event.screenId) : old,
          )
          queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        }
      })
    }
    client.activate()
    clientRef.current = client
    return () => {
      client.deactivate()
      clientRef.current = null
    }
  }, [enabled, queryClient])
}
