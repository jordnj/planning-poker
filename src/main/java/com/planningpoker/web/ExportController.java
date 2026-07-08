package com.planningpoker.web;

import com.planningpoker.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.planningpoker.web.SessionController.principalOf;

@RestController
@RequestMapping("/api/sessions/{code}/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<?> export(@PathVariable String code,
                                    @RequestParam(defaultValue = "json") String format,
                                    Authentication authentication) {
        return switch (format.toLowerCase()) {
            case "json" -> {
                Map<String, Object> body = exportService.exportJson(code, principalOf(authentication));
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=session-" + code + ".json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
            case "csv" -> {
                String csv = exportService.exportCsv(code, principalOf(authentication));
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=session-" + code + ".csv")
                        .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                        .body(csv);
            }
            default -> throw ApiException.badRequest("INVALID_FORMAT", "format must be csv or json");
        };
    }
}
