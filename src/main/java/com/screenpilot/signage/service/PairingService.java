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

@Service
public class PairingService {

    /** Unambiguous alphabet: no 0/O, 1/I/L. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
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

    @Transactional
    public PlayerDtos.PairCodeResponse requestCode(PlayerDtos.PairRequest req) {
        String code;
        do {
            code = randomCode();
        } while (pairingRepository.findByCodeAndStatus(code, PairingCode.Status.PENDING).isPresent());
        Instant expires = Instant.now().plus(Duration.ofMinutes(props.getPlayer().getPairingCodeTtlMinutes()));
        PairingCode pairing = new PairingCode(code, req == null ? null : req.deviceInfo(), expires);
        pairingRepository.save(pairing);
        return new PlayerDtos.PairCodeResponse(code, expires, 3000);
    }

    @Transactional
    public PlayerDtos.PairPollResponse poll(String code) {
        PairingCode pairing = pairingRepository.findFirstByCodeOrderByCreatedAtDesc(normalize(code))
                .orElseThrow(() -> ApiException.notFound("Unknown pairing code"));
        if (pairing.getStatus() == PairingCode.Status.PENDING && pairing.getExpiresAt().isBefore(Instant.now())) {
            pairing.setStatus(PairingCode.Status.EXPIRED);
            pairingRepository.save(pairing);
        }
        if (pairing.getStatus() == PairingCode.Status.PAIRED && pairing.getScreen() != null) {
            Screen screen = pairing.getScreen();
            return new PlayerDtos.PairPollResponse("PAIRED", screen.getDeviceToken(), screen.getId(), screen.getName());
        }
        return new PlayerDtos.PairPollResponse(pairing.getStatus().name(), null, null, null);
    }

    /** Called from the portal: binds a pending code to a new screen and issues a device token. */
    @Transactional
    public ScreenDtos.ScreenResponse pair(ScreenDtos.PairScreenRequest req) {
        String code = normalize(req.code());
        PairingCode pairing = pairingRepository.findByCodeAndStatus(code, PairingCode.Status.PENDING)
                .orElseThrow(() -> ApiException.badRequest("Pairing code not found or already used"));
        if (pairing.getExpiresAt().isBefore(Instant.now())) {
            pairing.setStatus(PairingCode.Status.EXPIRED);
            pairingRepository.save(pairing);
            throw ApiException.badRequest("This pairing code has expired. Ask the screen for a new one.");
        }

        ScreenDtos.ScreenResponse created = screenService.create(req.screen());
        Screen screen = screenRepository.findById(created.id()).orElseThrow();
        screen.setDeviceToken(generateDeviceToken());
        screen.setPaired(true);
        screenRepository.save(screen);

        pairing.setScreen(screen);
        pairing.setStatus(PairingCode.Status.PAIRED);
        pairingRepository.save(pairing);

        ScreenDtos.ScreenResponse dto = mapper.toDto(screen);
        events.screenUpdated(dto);
        return dto;
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generateDeviceToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
