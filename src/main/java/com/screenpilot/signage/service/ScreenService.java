package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.ScreenGroup;
import com.screenpilot.signage.dto.ScreenDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.ScreenGroupRepository;
import com.screenpilot.signage.repo.ScreenRepository;
import com.screenpilot.signage.security.AppPrincipal;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.ws.ScreenEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ScreenService {

    private final ScreenRepository screenRepository;
    private final ScreenGroupRepository groupRepository;
    private final ScreenMapper mapper;
    private final ScreenEventPublisher events;

    public ScreenService(ScreenRepository screenRepository, ScreenGroupRepository groupRepository,
                         ScreenMapper mapper, ScreenEventPublisher events) {
        this.screenRepository = screenRepository;
        this.groupRepository = groupRepository;
        this.mapper = mapper;
        this.events = events;
    }

    /** All screens the current portal user is allowed to see. */
    @Transactional(readOnly = true)
    public List<Screen> accessibleScreens() {
        AppPrincipal user = CurrentUser.get();
        List<Screen> all = screenRepository.findAll();
        if (user.unrestricted()) {
            return all;
        }
        return all.stream()
                .filter(s -> s.getGroup() != null && user.groupIds().contains(s.getGroup().getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScreenDtos.ScreenResponse> list(UUID groupId, String state, String city, String status, String search) {
        return accessibleScreens().stream()
                .filter(s -> groupId == null || (s.getGroup() != null && groupId.equals(s.getGroup().getId())))
                .filter(s -> state == null || state.isBlank() || state.equalsIgnoreCase(s.getState()))
                .filter(s -> city == null || city.isBlank() || city.equalsIgnoreCase(s.getCity()))
                .filter(s -> status == null || status.isBlank() || s.getStatus().name().equalsIgnoreCase(status))
                .filter(s -> matchesSearch(s, search))
                .sorted(Comparator.comparing(Screen::getName, String.CASE_INSENSITIVE_ORDER))
                .map(mapper::toDto)
                .toList();
    }

    private boolean matchesSearch(Screen s, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String q = search.toLowerCase();
        return (s.getName() != null && s.getName().toLowerCase().contains(q))
                || (s.getStoreName() != null && s.getStoreName().toLowerCase().contains(q))
                || (s.getCity() != null && s.getCity().toLowerCase().contains(q));
    }

    @Transactional(readOnly = true)
    public Screen getAccessible(UUID id) {
        Screen screen = screenRepository.findById(id).orElseThrow(() -> ApiException.notFound("Screen not found"));
        AppPrincipal user = CurrentUser.get();
        if (!user.unrestricted()
                && (screen.getGroup() == null || !user.groupIds().contains(screen.getGroup().getId()))) {
            throw ApiException.forbidden("You do not have access to this screen");
        }
        return screen;
    }

    @Transactional(readOnly = true)
    public ScreenDtos.ScreenResponse get(UUID id) {
        return mapper.toDto(getAccessible(id));
    }

    @Transactional
    public ScreenDtos.ScreenResponse create(ScreenDtos.SaveScreenRequest req) {
        Screen screen = new Screen(req.name().trim());
        apply(screen, req);
        Screen saved = screenRepository.save(screen);
        ScreenDtos.ScreenResponse dto = mapper.toDto(saved);
        events.screenUpdated(dto);
        return dto;
    }

    @Transactional
    public ScreenDtos.ScreenResponse update(UUID id, ScreenDtos.SaveScreenRequest req) {
        Screen screen = getAccessible(id);
        screen.setName(req.name().trim());
        apply(screen, req);
        ScreenDtos.ScreenResponse dto = mapper.toDto(screenRepository.save(screen));
        events.screenUpdated(dto);
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        Screen screen = getAccessible(id);
        screenRepository.delete(screen);
        events.screenRemoved(id);
    }

    @Transactional
    public void bulkAssignGroup(ScreenDtos.BulkGroupRequest req) {
        ScreenGroup group = req.groupId() == null ? null
                : groupRepository.findById(req.groupId()).orElseThrow(() -> ApiException.notFound("Group not found"));
        for (UUID screenId : req.screenIds()) {
            Screen screen = getAccessible(screenId);
            screen.setGroup(group);
            events.screenUpdated(mapper.toDto(screenRepository.save(screen)));
        }
    }

    private void apply(Screen screen, ScreenDtos.SaveScreenRequest req) {
        screen.setStoreName(req.storeName());
        screen.setCity(req.city());
        screen.setState(req.state());
        screen.setOrientation(req.orientation());
        screen.setResolution(req.resolution());
        screen.setLatitude(req.latitude());
        screen.setLongitude(req.longitude());
        if (req.groupId() != null) {
            ScreenGroup group = groupRepository.findById(req.groupId())
                    .orElseThrow(() -> ApiException.badRequest("Screen group does not exist"));
            AppPrincipal user = CurrentUser.get();
            if (!user.canAccessGroup(group.getId())) {
                throw ApiException.forbidden("You do not have access to this screen group");
            }
            screen.setGroup(group);
        } else {
            screen.setGroup(null);
        }
    }
}
