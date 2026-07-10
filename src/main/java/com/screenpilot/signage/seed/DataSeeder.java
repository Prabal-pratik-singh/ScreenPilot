package com.screenpilot.signage.seed;

import com.screenpilot.signage.config.AppProperties;
import com.screenpilot.signage.domain.Role;
import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.ScreenGroup;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.repo.ScreenGroupRepository;
import com.screenpilot.signage.repo.ScreenRepository;
import com.screenpilot.signage.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final ScreenGroupRepository groupRepository;
    private final ScreenRepository screenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public DataSeeder(UserRepository userRepository, ScreenGroupRepository groupRepository,
                      ScreenRepository screenRepository, PasswordEncoder passwordEncoder, AppProperties props) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.screenRepository = screenRepository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Seed data already present, skipping");
            printCredentials();
            return;
        }

        ScreenGroup ranchi = groupRepository.save(new ScreenGroup("Ranchi", "Stores in and around Ranchi"));
        ScreenGroup patna = groupRepository.save(new ScreenGroup("Patna", "Stores in and around Patna"));
        ScreenGroup kolkata = groupRepository.save(new ScreenGroup("Kolkata", "Stores in and around Kolkata"));

        User admin = new User(props.getSeed().getAdminEmail(),
                passwordEncoder.encode(props.getSeed().getAdminPassword()),
                "ScreenPilot Admin", Role.SUPER_ADMIN);
        userRepository.save(admin);

        User ranchiManager = new User("content.ranchi@screenpilot.in",
                passwordEncoder.encode("Content@123"), "Ranchi Content Manager", Role.CONTENT_MANAGER);
        ranchiManager.setGroups(Set.of(ranchi));
        userRepository.save(ranchiManager);

        User viewer = new User("viewer@screenpilot.in",
                passwordEncoder.encode("Viewer@123"), "Reports Viewer", Role.VIEWER);
        userRepository.save(viewer);

        Instant now = Instant.now();
        seedScreen("Ranchi Main Road — Entrance", "Ranchi Main Road", "Ranchi", "Jharkhand", ranchi,
                23.3441, 85.3096, now.minus(Duration.ofMinutes(10)));
        seedScreen("Ranchi Main Road — Checkout", "Ranchi Main Road", "Ranchi", "Jharkhand", ranchi,
                23.3446, 85.3101, now.minus(Duration.ofMinutes(45)));
        seedScreen("Lalpur — Aisle Display", "Lalpur", "Ranchi", "Jharkhand", ranchi,
                23.3700, 85.3346, now.minus(Duration.ofHours(2)));
        seedScreen("Harmu — Window Screen", "Harmu", "Ranchi", "Jharkhand", ranchi,
                23.3441, 85.2900, now.minus(Duration.ofHours(30)));
        seedScreen("Kanke Road — Entrance", "Kanke Road", "Ranchi", "Jharkhand", ranchi,
                23.4241, 85.3186, null);
        seedScreen("Jamshedpur Bistupur — Entrance", "Bistupur", "Jamshedpur", "Jharkhand", ranchi,
                22.8046, 86.2029, now.minus(Duration.ofDays(3)));

        seedScreen("Boring Road — Entrance", "Boring Road", "Patna", "Bihar", patna,
                25.6093, 85.1235, now.minus(Duration.ofMinutes(90)));
        seedScreen("Kankarbagh — Checkout", "Kankarbagh", "Patna", "Bihar", patna,
                25.5941, 85.1376, now.minus(Duration.ofHours(5)));
        seedScreen("Danapur — Aisle Display", "Danapur", "Patna", "Bihar", patna,
                25.6333, 85.0460, now.minus(Duration.ofHours(26)));
        seedScreen("Patliputra — Window Screen", "Patliputra", "Patna", "Bihar", patna,
                25.6280, 85.1130, now.minus(Duration.ofDays(5)));

        seedScreen("Salt Lake Sector V — Entrance", "Salt Lake", "Kolkata", "West Bengal", kolkata,
                22.5867, 88.4171, now.minus(Duration.ofMinutes(25)));
        seedScreen("Gariahat — Checkout", "Gariahat", "Kolkata", "West Bengal", kolkata,
                22.5205, 88.3661, now.minus(Duration.ofHours(52)));

        log.info("Seeded 3 screen groups, 3 users and 12 demo screens");
        printCredentials();
    }

    private void seedScreen(String name, String store, String city, String state, ScreenGroup group,
                            double lat, double lng, Instant lastHeartbeat) {
        Screen s = new Screen(name);
        s.setStoreName(store);
        s.setCity(city);
        s.setState(state);
        s.setGroup(group);
        s.setOrientation(name.contains("Window") ? Screen.Orientation.PORTRAIT : Screen.Orientation.LANDSCAPE);
        s.setResolution("1920x1080");
        s.setLatitude(lat);
        s.setLongitude(lng);
        s.setStatus(Screen.Status.OFFLINE);
        s.setLastHeartbeatAt(lastHeartbeat);
        s.setAppVersion("1.0.0");
        s.setPaired(false);
        screenRepository.save(s);
    }

    private void printCredentials() {
        log.info("==============================================================");
        log.info("  Portal login: {}  /  {}", props.getSeed().getAdminEmail(), props.getSeed().getAdminPassword());
        log.info("  Extra users : content.ranchi@screenpilot.in / Content@123 (CONTENT_MANAGER, Ranchi only)");
        log.info("                viewer@screenpilot.in / Viewer@123 (VIEWER)");
        log.info("==============================================================");
    }
}
