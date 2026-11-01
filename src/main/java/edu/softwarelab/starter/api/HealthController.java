package edu.softwarelab.starter.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness probes.
 *
 * <p>{@code GET /health} — liveness probe; always returns {@code 200 OK} with
 * body {@code {"status":"ok"}}. The process is alive iff this endpoint answers.
 *
 * <p>{@code GET /ready} — readiness probe; runs {@code SELECT 1} against the
 * PostgreSQL database via {@link JdbcTemplate} and pings the Kafka admin client
 * (which lazily connects to the bootstrap servers). Returns
 * {@code {"status":"ok","dependencies":{"db":"up","kafka":"up"}}} on success
 * or {@code 503} with a {@code down} marker on failure.
 *
 * <p>Per the OpenAPI contract, both endpoints return {@code application/json}
 * (not {@code application/problem+json}).
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaAdmin kafkaAdmin;

    public HealthController(JdbcTemplate jdbcTemplate, KafkaAdmin kafkaAdmin) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaAdmin = kafkaAdmin;
    }

    /* ----------------------------------------------------------------- */

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping(value = "/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, String> deps = new LinkedHashMap<>();
        boolean allUp = true;

        // Database check — runs SELECT 1.
        String dbStatus;
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(one)) {
                dbStatus = "up";
            } else {
                dbStatus = "down";
                allUp = false;
            }
        } catch (Exception ex) {
            log.warn("Readiness DB check failed: {}", ex.getMessage());
            dbStatus = "down";
            allUp = false;
        }
        deps.put("db", dbStatus);

        // Kafka check — ping the admin client (lazily connects to the bootstrap).
        String kafkaStatus;
        try {
            kafkaAdmin.describeTopics();
            kafkaStatus = "up";
        } catch (Exception ex) {
            log.warn("Readiness Kafka check failed: {}", ex.getMessage());
            kafkaStatus = "down";
            allUp = false;
        }
        deps.put("kafka", kafkaStatus);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "ok" : "degraded");
        body.put("dependencies", deps);

        if (allUp) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
