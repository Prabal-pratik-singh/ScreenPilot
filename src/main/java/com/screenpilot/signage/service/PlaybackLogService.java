package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.PlaybackLog;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.PlaybackLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores "proof of play" records — one row per media item a screen actually
 * played, with start/end times. Players upload these in batches; reports and
 * exports are built on top of this table.
 */
@Service
public class PlaybackLogService {

    public record LogEntry(UUID itemId, UUID mediaId, UUID scheduleId, UUID playlistId,
                           String itemTitle, String itemType, Instant startedAt, Instant endedAt) {
    }

    public record LogBatch(List<LogEntry> logs) {
    }

    private final PlaybackLogRepository repository;

    public PlaybackLogService(PlaybackLogRepository repository) {
        this.repository = repository;
    }

    /** Persists a batch of playback entries from one screen; returns how many were accepted. */
    @Transactional
    public int saveBatch(UUID screenId, LogBatch batch) {
        // 1. empty batches are a no-op, oversized ones are rejected outright
        if (batch == null || batch.logs() == null || batch.logs().isEmpty()) {
            return 0;
        }
        if (batch.logs().size() > 1000) {
            throw ApiException.badRequest("Batch too large (max 1000 entries)");
        }
        // 2. drop entries with missing or reversed timestamps, then map to entities
        List<PlaybackLog> entities = batch.logs().stream()
                .filter(e -> e.startedAt() != null && e.endedAt() != null && !e.endedAt().isBefore(e.startedAt()))
                .map(e -> {
                    PlaybackLog log = new PlaybackLog();
                    log.setScreenId(screenId);
                    log.setItemId(e.itemId());
                    log.setMediaId(e.mediaId());
                    log.setScheduleId(e.scheduleId());
                    log.setPlaylistId(e.playlistId());
                    log.setItemTitle(e.itemTitle());
                    log.setItemType(e.itemType());
                    log.setStartedAt(e.startedAt());
                    log.setEndedAt(e.endedAt());
                    log.setDurationSeconds(Duration.between(e.startedAt(), e.endedAt()).toMillis() / 1000.0);
                    return log;
                })
                .toList();
        // 3. one bulk insert for the whole batch
        repository.saveAll(entities);
        return entities.size();
    }
}
