package com.processdesk.assets;

import com.processdesk.harness.AuditLog;
import com.processdesk.harness.GateResult;
import com.processdesk.harness.ValidationGates;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assets;
    private final ValidationGates gates;
    private final com.processdesk.harness.DecisionGates decisionGates;
    private final AuditLog audit;

    public AssetController(AssetService assets, ValidationGates gates,
                           com.processdesk.harness.DecisionGates decisionGates,
                           AuditLog audit) {
        this.assets = assets;
        this.gates = gates;
        this.decisionGates = decisionGates;
        this.audit = audit;
    }

    public record AssetSummary(String name) {}

    public record AssetContent(String name, String xml) {}

    public record SaveRequest(String xml) {}

    public record SaveResponse(boolean ok, List<GateResult> gates, String message) {}

    @GetMapping
    public Map<String, List<AssetSummary>> list() {
        return Map.of("assets", assets.list().stream().map(AssetSummary::new).toList());
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> get(@PathVariable String name) throws IOException {
        try {
            return ResponseEntity.ok(new AssetContent(name, assets.read(name)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid asset name."));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Asset not found."));
        }
    }

    /**
     * Saving re-runs the gates rather than trusting the client. A proposal that was
     * validated at propose-time could still arrive here modified, so the last check
     * before disk happens here.
     *
     * <p>Which gates depends on the file. {@link ValidationGates} hands the document to the
     * BPMN parser, which no DMN file will ever satisfy — so every decision-model save was
     * refused with "This file doesn't contain a process", after the assistant had correctly
     * made the change and shown it on the canvas. The edit worked and applying it did not,
     * which reads as the assistant being broken rather than the save path.
     */
    @PutMapping("/{name}")
    public ResponseEntity<SaveResponse> save(@PathVariable String name, @RequestBody SaveRequest request)
            throws IOException {
        if (request.xml() == null || request.xml().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new SaveResponse(false, List.of(), "The request didn't include any file content."));
        }
        boolean isDecision = com.processdesk.harness.DecisionProjection
                .looksLikeDecisionModel(request.xml());
        // No baseline here: this is a file arriving to be written, not a proposed change to
        // compare against, so the coverage gate reports on the document as it stands.
        List<GateResult> results = isDecision
                ? decisionGates.run(request.xml())
                : gates.run(request.xml());
        boolean passed = isDecision
                ? com.processdesk.harness.DecisionGates.allPassed(results)
                : ValidationGates.allPassed(results);
        if (!passed) {
            audit.record("save-rejected", Map.of("asset", name, "gates", results));
            return ResponseEntity.unprocessableEntity().body(new SaveResponse(false, results,
                    "The change didn't pass all the safety checks, so nothing was saved."));
        }
        assets.write(name, request.xml());
        audit.record("saved", Map.of("asset", name));
        return ResponseEntity.ok(new SaveResponse(true, results, "Saved."));
    }
}
