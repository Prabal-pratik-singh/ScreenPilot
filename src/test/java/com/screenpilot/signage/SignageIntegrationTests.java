package com.screenpilot.signage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against a real, throwaway PostgreSQL (Testcontainers):
 * the same crown-jewel flows the demo depends on — auth + RBAC, the pairing
 * handshake with hashed tokens, signed media URLs, and schedule conflicts.
 *
 * disabledWithoutDocker: when the local Docker daemon isn't usable by
 * Testcontainers (a known Windows npipe/docker-java incompatibility on this
 * dev machine), the suite skips instead of failing — GitHub Actions CI is
 * the enforcing run.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignageIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate rest;

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String login(String email, String password) {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", password), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) res.getBody().get("accessToken");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ---------------------------------------------------------------- auth + rbac

    @Test
    void wrongPasswordIsRejected() {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/login",
                Map.of("email", "admin@screenpilot.in", "password", "nope"), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminSeesSeededScreens() {
        String token = login("admin@screenpilot.in", "ScreenPilot@123");
        ResponseEntity<List> res = rest.exchange("/api/screens", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().size()).isGreaterThanOrEqualTo(12);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void viewerIsReadOnly() {
        String token = login("viewer@screenpilot.in", "Viewer@123");

        ResponseEntity<List> read = rest.exchange("/api/screens", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> create = rest.exchange("/api/screens", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "hack", "orientation", "LANDSCAPE"), bearer(token)), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> users = rest.exchange("/api/users", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void groupRestrictedManagerSeesOnlyTheirScreens() {
        String token = login("content.ranchi@screenpilot.in", "Content@123");
        ResponseEntity<List> res = rest.exchange("/api/screens", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> screens = res.getBody();
        assertThat(screens).isNotEmpty();
        assertThat(screens).allSatisfy(s -> assertThat(s.get("state")).isEqualTo("Jharkhand"));
    }

    // ---------------------------------------------------------------- pairing + heartbeat

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pairingFlowDeliversTokenAndHeartbeatWorks() {
        ResponseEntity<Map> codeRes = rest.postForEntity("/api/player/pair/request",
                Map.of("deviceInfo", "it-test"), Map.class);
        assertThat(codeRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = (String) codeRes.getBody().get("code");
        assertThat(code).hasSize(6);

        String admin = login("admin@screenpilot.in", "ScreenPilot@123");
        ResponseEntity<Map> paired = rest.exchange("/api/screens/pair", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code,
                        "screen", Map.of("name", "IT Screen", "orientation", "LANDSCAPE")), bearer(admin)),
                Map.class);
        assertThat(paired.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> poll = rest.getForEntity("/api/player/pair/poll/" + code, Map.class);
        String deviceToken = (String) poll.getBody().get("deviceToken");
        assertThat(poll.getBody().get("status")).isEqualTo("PAIRED");
        assertThat(deviceToken).isNotBlank();

        HttpHeaders device = new HttpHeaders();
        device.set("X-Device-Token", deviceToken);
        device.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> hb = rest.exchange("/api/player/heartbeat", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "IDLE"), device), Map.class);
        assertThat(hb.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hb.getBody().get("ok")).isEqualTo(true);

        // a nonsense token must be rejected: proves lookups go through the hash
        device.set("X-Device-Token", "not-a-real-token");
        ResponseEntity<Map> bad = rest.exchange("/api/player/heartbeat", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "IDLE"), device), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- signed media urls

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mediaBinariesRequireValidSignature() {
        String admin = login("admin@screenpilot.in", "ScreenPilot@123");
        ResponseEntity<List> media = rest.exchange("/api/media", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), List.class);
        assertThat(media.getBody()).isNotEmpty();
        Map<String, Object> asset = (Map<String, Object>) media.getBody().get(0);
        String signedUrl = (String) asset.get("fileUrl");
        String id = (String) asset.get("id");
        assertThat(signedUrl).contains("exp=").contains("sig=");

        assertThat(rest.getForEntity("/api/media/" + id + "/file", byte[].class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity(signedUrl, byte[].class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(signedUrl + "0", byte[].class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- schedule conflicts

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void conflictRulesTimedBeatsAllDayWithoutConflict() {
        String admin = login("admin@screenpilot.in", "ScreenPilot@123");

        // stale runs may have left test schedules behind — start clean
        List<Map<String, Object>> existing = rest.exchange("/api/schedules", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), List.class).getBody();
        for (Map<String, Object> s : existing) {
            if ("IT all-day".equals(s.get("name"))) {
                rest.exchange("/api/schedules/" + s.get("id"), HttpMethod.DELETE,
                        new HttpEntity<>(bearer(admin)), Void.class);
            }
        }

        List<Map<String, Object>> screens = rest.exchange("/api/screens", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), List.class).getBody();
        String screenId = (String) screens.get(0).get("id");
        List<Map<String, Object>> playlists = rest.exchange("/api/playlists", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), List.class).getBody();
        String playlistId;
        if (playlists.isEmpty()) {
            Map created = rest.exchange("/api/playlists", HttpMethod.POST,
                    new HttpEntity<>(Map.of("name", "IT playlist"), bearer(admin)), Map.class).getBody();
            playlistId = (String) created.get("id");
        } else {
            playlistId = (String) playlists.get(0).get("id");
        }

        Map<String, Object> allDay = new java.util.HashMap<>();
        allDay.put("name", "IT all-day");
        allDay.put("contentType", "PLAYLIST");
        allDay.put("playlistId", playlistId);
        allDay.put("screenIds", List.of(screenId));
        allDay.put("allDay", true);
        ResponseEntity<Map> created = rest.exchange("/api/schedules", HttpMethod.POST,
                new HttpEntity<>(allDay, bearer(admin)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        // a second all-day loop on the same screen = same-specificity overlap -> conflict
        ResponseEntity<Map> sameKind = rest.exchange("/api/schedules/preview-conflicts", HttpMethod.POST,
                new HttpEntity<>(allDay, bearer(admin)), Map.class);
        assertThat((List) sameKind.getBody().get("conflicts")).hasSize(1);

        // a timed window overlays the all-day loop by design (priority rule) -> no conflict
        Map<String, Object> timed = new java.util.HashMap<>(allDay);
        timed.put("name", "IT evening");
        timed.put("allDay", false);
        timed.put("startTime", "18:00");
        timed.put("endTime", "22:00");
        ResponseEntity<Map> layered = rest.exchange("/api/schedules/preview-conflicts", HttpMethod.POST,
                new HttpEntity<>(timed, bearer(admin)), Map.class);
        assertThat((List) layered.getBody().get("conflicts")).isEmpty();

        // leave the shared database the way we found it
        rest.exchange("/api/schedules/" + created.getBody().get("id"), HttpMethod.DELETE,
                new HttpEntity<>(bearer(admin)), Void.class);
    }
}
