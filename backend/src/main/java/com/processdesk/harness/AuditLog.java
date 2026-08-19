package com.processdesk.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only record of every AI proposal and what happened to it. This is the
 * accountability trail: what was asked, what was proposed, whether it passed the
 * gates, and whether a human accepted it.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public AuditLog(@Value("${processdesk.audit-file:./audit.log}") String auditFile) {
        this.file = Path.of(auditFile);
    }

    public void record(String event, Map<String, Object> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("event", event);
        entry.putAll(fields);
        try {
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Could not write audit entry", e);
        }
    }
}
