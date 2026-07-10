package com.screenpilot.signage.web;

import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.service.ExportService;
import com.screenpilot.signage.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('VIEWER')")
public class ReportController {

    private final ReportService reportService;
    private final ExportService exportService;

    public ReportController(ReportService reportService, ExportService exportService) {
        this.reportService = reportService;
        this.exportService = exportService;
    }

    @GetMapping("/proof-of-play")
    public ReportService.ProofOfPlayReport proofOfPlay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> screenIds,
            @RequestParam(required = false) List<UUID> mediaIds) {
        return reportService.proofOfPlay(from, to, screenIds, mediaIds);
    }

    @GetMapping("/uptime")
    public ReportService.UptimeReport uptime(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.uptime(from, to);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam String report,
            @RequestParam String format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> screenIds,
            @RequestParam(required = false) List<UUID> mediaIds) throws Exception {
        byte[] bytes;
        String base;
        if ("proof-of-play".equalsIgnoreCase(report)) {
            ReportService.ProofOfPlayReport data = reportService.proofOfPlay(from, to, screenIds, mediaIds);
            base = "screenpilot-proof-of-play-" + from + "_" + to;
            bytes = "pdf".equalsIgnoreCase(format)
                    ? exportService.proofOfPlayPdf(data, from, to)
                    : exportService.proofOfPlayXlsx(data, from, to);
        } else if ("uptime".equalsIgnoreCase(report)) {
            ReportService.UptimeReport data = reportService.uptime(from, to);
            base = "screenpilot-uptime-" + from + "_" + to;
            bytes = "pdf".equalsIgnoreCase(format)
                    ? exportService.uptimePdf(data, from, to)
                    : exportService.uptimeXlsx(data, from, to);
        } else {
            throw ApiException.badRequest("Unknown report: " + report + " (use proof-of-play or uptime)");
        }
        boolean pdf = "pdf".equalsIgnoreCase(format);
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + base + (pdf ? ".pdf" : ".xlsx") + "\"")
                .body(bytes);
    }
}
