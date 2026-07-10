package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.ScreenGroup;
import com.screenpilot.signage.dto.GroupDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.ScreenGroupRepository;
import com.screenpilot.signage.repo.ScreenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final ScreenGroupRepository groupRepository;
    private final ScreenRepository screenRepository;

    public GroupService(ScreenGroupRepository groupRepository, ScreenRepository screenRepository) {
        this.groupRepository = groupRepository;
        this.screenRepository = screenRepository;
    }

    @Transactional(readOnly = true)
    public List<GroupDtos.GroupResponse> list() {
        Map<UUID, Long> counts = screenRepository.findAll().stream()
                .filter(s -> s.getGroup() != null)
                .collect(Collectors.groupingBy(s -> s.getGroup().getId(), Collectors.counting()));
        return groupRepository.findAll().stream()
                .sorted(Comparator.comparing(ScreenGroup::getName))
                .map(g -> new GroupDtos.GroupResponse(g.getId(), g.getName(), g.getDescription(),
                        counts.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    @Transactional
    public GroupDtos.GroupResponse create(GroupDtos.SaveGroupRequest req) {
        groupRepository.findByNameIgnoreCase(req.name().trim()).ifPresent(g -> {
            throw ApiException.conflict("A group with this name already exists");
        });
        ScreenGroup group = groupRepository.save(new ScreenGroup(req.name().trim(), req.description()));
        return new GroupDtos.GroupResponse(group.getId(), group.getName(), group.getDescription(), 0);
    }

    @Transactional
    public GroupDtos.GroupResponse update(UUID id, GroupDtos.SaveGroupRequest req) {
        ScreenGroup group = groupRepository.findById(id).orElseThrow(() -> ApiException.notFound("Group not found"));
        groupRepository.findByNameIgnoreCase(req.name().trim()).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw ApiException.conflict("A group with this name already exists");
            }
        });
        group.setName(req.name().trim());
        group.setDescription(req.description());
        groupRepository.save(group);
        long count = screenRepository.findAll().stream()
                .filter(s -> s.getGroup() != null && id.equals(s.getGroup().getId())).count();
        return new GroupDtos.GroupResponse(group.getId(), group.getName(), group.getDescription(), count);
    }

    @Transactional
    public void delete(UUID id) {
        ScreenGroup group = groupRepository.findById(id).orElseThrow(() -> ApiException.notFound("Group not found"));
        boolean inUse = screenRepository.findAll().stream()
                .anyMatch(s -> s.getGroup() != null && id.equals(s.getGroup().getId()));
        if (inUse) {
            throw ApiException.conflict("This group still contains screens. Move them to another group first.");
        }
        groupRepository.delete(group);
    }

    @Transactional(readOnly = true)
    public List<Screen> screensInGroup(UUID groupId) {
        return screenRepository.findByGroupIdIn(List.of(groupId));
    }
}
