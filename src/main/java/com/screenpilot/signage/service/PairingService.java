package com.screenpilot.signage.service;

import com.screenpilot.signage.config.AppProperties;
import com.screenpilot.signage.domain.PairingCode;
import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.PlayerDtos;
import com.screenpilot.signage.dto.ScreenDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.PairingCodeRepository;
import com.screenpilot.signage.repo.ScreenRepository;
import com.screenpilot.signage.ws.ScreenEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Pairs a physical TV with the portal, TV-login style: the device shows a
 * short code, an admin types it into the portal, and the device — which keeps
 * polling — receives its permanent device token exactly once. Codes expire
 * after a few minutes and plaintext tokens are swept from the DB afterwards.
 */
@Service
public class PairingService {

    // humans read this code off a TV across the room and type it into the
    // portal, so every lookalike character is banned (no confusing O with 0)
    /** Unambiguous alphabet: no 0/O, 1/I/L. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    // SecureRandom = cryptographically strong randomness, so neither pairing
    // codes nor device tokens can be predicted or reproduced by an attacker
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PairingCodeRepository pairingRepository;
    private final ScreenRepository screenRepository;
    private final ScreenService screenService;
    private final ScreenMapper mapper;
    private final ScreenEventPublisher events;
    private final AppProperties props;

    public PairingService(PairingCodeRepository pairingRepository, ScreenRepository screenRepository,
                          ScreenService screenService, ScreenMapper mapper,
                          ScreenEventPublisher events, AppProperties props) {
        this.pairingRepository = pairingRepository;
        this.screenRepository = screenRepository;
        this.screenService = screenService;
        this.mapper = mapper;
        this.events = events;
        this.props = props;
    }

    /** Device side, step 1: issues a fresh 6-character code the TV shows on screen. */
    @Transactional
    public PlayerDtos.PairCodeResponse requestCode(PlayerDtos.PairRequest req) {
        // 1. pick a random code that is not already pending
        // only PENDING rows block reuse — expired/used codes may repeat later,
        // so the small 6-character space never runs out over time
        String code;
        do {
            code = randomCode();
        } while (pairingRepository.findByCodeAndStatus(code, PairingCode.Status.PENDING).isPresent());
        // 2. store it with a TTL; the response tells the device how often to poll (ms)
        // TTL comes from config (app.player.pairing-code-ttl-minutes, default 15)
        Instant expires = Instant.now().plus(Duration.ofMinutes(props.getPlayer().getPairingCodeTtlMinutes()));
        PairingCode pairing = new PairingCode(code, req == null ? null : req.deviceInfo(), expires);
        pairingRepository.save(pairing);
        // 3000 = "poll every 3 seconds" hint for the device
        return new PlayerDtos.PairCodeResponse(code, expires, 3000);
    }

    /** Device side, step 2: the TV polls until an admin pairs the code (or it expires). */
    @Transactional
    public PlayerDtos.PairPollResponse poll(String code) {
        // codes may repeat over time (see requestCode), so fetch the NEWEST row for this code
        PairingCode pairing = pairingRepository.findFirstByCodeOrderByCreatedAtDesc(normalize(code))
                .orElseThrow(() -> ApiException.notFound("Unknown pairing code"));
        // 1. lazily expire a PENDING code whose TTL has passed
        // (done here at read time — no background job is needed for this flip,
        // and the device's next poll immediately shows "EXPIRED")
        if (pairing.getStatus() == PairingCode.Status.PENDING && pairing.getExpiresAt().isBefore(Instant.now())) {
            pairing.setStatus(PairingCode.Status.EXPIRED);
            pairingRepository.save(pairing);
        }
        // 2. once paired, hand the device its token (still available until the sweep clears it)
        // this is the one-time pickup: the plaintext token exists only on this
        // short-lived pairing row, never anywhere else in the database
        if (pairing.getStatus() == PairingCode.Status.PAIRED && pairing.getScreen() != null
                && pairing.getDeviceTokenPlain() != null) {
            Screen screen = pairing.getScreen();
            return new PlayerDtos.PairPollResponse("PAIRED", pairing.getDeviceTokenPlain(), screen.getId(), screen.getName());
        }
        // otherwise just report the current state (PENDING, EXPIRED, or PAIRED
        // with the token already swept) so the device knows whether to keep polling
        return new PlayerDtos.PairPollResponse(pairing.getStatus().name(), null, null, null);
    }

    /** Called from the portal: binds a pending code to a new screen and issues a device token. */
    @Transactional
    public ScreenDtos.ScreenResponse pair(ScreenDtos.PairScreenRequest req) {
        // 1. validate the pairing code is still PENDING and not expired
        String code = normalize(req.code());
        PairingCode pairing = pairingRepository.findByCodeAndStatus(code, PairingCode.Status.PENDING)
                .orElseThrow(() -> ApiException.badRequest("Pairing code not found or already used"));
        // even a still-PENDING row is refused after its TTL; it is flipped to
        // EXPIRED so future polls and pair attempts all see a consistent state
        if (pairing.getExpiresAt().isBefore(Instant.now())) {
            pairing.setStatus(PairingCode.Status.EXPIRED);
            pairingRepository.save(pairing);
            throw ApiException.badRequest("This pairing code has expired. Ask the screen for a new one.");
        }

        // 2. create the screen record from the details the admin filled in
        ScreenDtos.ScreenResponse created = screenService.create(req.screen());
        Screen screen = screenRepository.findById(created.id()).orElseThrow();
        // 3. mint the device token
        // DB stores only the SHA-256 hash; the plaintext rides the short-lived
        // pairing row so the polling player can collect it once
        String plainToken = generateDeviceToken();
        // hashing means a leaked database dump contains no usable device tokens
        screen.setDeviceToken(com.screenpilot.signage.security.TokenHasher.sha256Hex(plainToken));
        // paired=true lets the screen authenticate with this token from now on
        screen.setPaired(true);
        screenRepository.save(screen);

        // 4. mark the code PAIRED so the device's next poll returns the token
        pairing.setScreen(screen);
        pairing.setStatus(PairingCode.Status.PAIRED);
        pairing.setDeviceTokenPlain(plainToken);
        pairingRepository.save(pairing);

        // 5. announce the new screen to portal viewers
        ScreenDtos.ScreenResponse dto = mapper.toDto(screen);
        events.screenUpdated(dto);
        return dto;
    }

    /** Clears plaintext tokens from expired pairing rows (they only exist for pickup). */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300000, initialDelay = 60000)
    @Transactional
    public void sweepExpiredPlaintextTokens() {
        // runs every 5 minutes (300000 ms), first run 60s after startup;
        // targets rows past their expiry that still hold a plaintext token
        for (PairingCode pairing : pairingRepository.findByDeviceTokenPlainIsNotNullAndExpiresAtBefore(Instant.now())) {
            // nulling the field means the database no longer stores any usable
            // secret — only the SHA-256 hash on the screen row remains
            pairing.setDeviceTokenPlain(null);
            pairingRepository.save(pairing);
        }
    }

    // codes are stored/compared uppercase without surrounding whitespace
    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    // 6 random characters from the 31-letter alphabet (~887 million combinations)
    private String randomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // 48 random bytes, URL-safe Base64 — the screen's long-lived credential
    // (384 bits of entropy; URL-safe Base64 uses no '+', '/' or '=' so the
    // token travels cleanly in headers and query strings without escaping)
    private String generateDeviceToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
