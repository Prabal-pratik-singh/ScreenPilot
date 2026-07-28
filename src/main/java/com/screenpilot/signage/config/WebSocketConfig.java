package com.screenpilot.signage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Turns on STOMP messaging over WebSocket so the server can push live updates
 * (screen status changes, new content, remote commands) to browsers and TV players
 * instead of making them poll. STOMP is a simple pub/sub protocol layered on top of
 * the raw WebSocket connection.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Clients connect at "/ws". Registered twice: once with SockJS (an HTTP fallback for
     * clients/proxies that block real WebSockets) and once as a plain WebSocket endpoint.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // registration 1: /ws with SockJS — browsers behind strict proxies or
        // firewalls that break real WebSockets silently fall back to HTTP
        // streaming/long-polling while the app code stays identical.
        // "*" origins: the portal and players may load from different
        // hosts/tunnels, so no origin is rejected here.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        // registration 2: the SAME /ws path as a plain raw WebSocket, for
        // clients that don't speak SockJS framing (e.g. the Android TV player
        // or any bare WebSocket/STOMP library)
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    /**
     * Uses the in-memory "simple broker": messages sent to "/topic/..." (broadcast) and
     * "/queue/..." (one-to-one) fan out to subscribers; client-to-server messages are
     * addressed with the "/app" prefix and land in @MessageMapping controller methods.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // "simple broker" = a lightweight in-memory broker inside this app (no
        // external RabbitMQ/ActiveMQ needed); it tracks who subscribed to which
        // destination and fans messages out — plenty for a single server
        registry.enableSimpleBroker("/topic", "/queue");
        // messages the CLIENT sends must start with /app (e.g.
        // /app/player/heartbeat) and are routed to @MessageMapping controller
        // methods instead of being broadcast to subscribers
        registry.setApplicationDestinationPrefixes("/app");
    }
}
