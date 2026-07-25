package com.planningpoker.web;

import com.planningpoker.dto.Dtos.ExportBundle;
import com.planningpoker.service.ExportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.planningpoker.web.SessionController.principalOf;

/**
 * Serves the export bundle. CSV and JSON files are rendered in the browser
 * from this payload: story titles and participant names are end-to-end
 * encrypted, so only a client holding the room key can produce a readable
 * file. No filename is set here — it would embed the session code in a
 * response header for no benefit.
 */
@RestController
@RequestMapping("/api/sessions/{code}/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<ExportBundle> export(@PathVariable String code, Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(exportService.export(code, principalOf(authentication)));
    }
}
