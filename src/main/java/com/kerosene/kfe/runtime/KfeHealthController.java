package com.kerosene.kfe.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.kfe.integration.KfeFinancialRailHealthAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHealthController {

    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<KfeFinancialRailHealthAdapter> railHealth;

    public KfeHealthController(
            ObjectProvider<DataSource> dataSource,
            ObjectProvider<KfeFinancialRailHealthAdapter> railHealth) {
        this.dataSource = dataSource;
        this.railHealth = railHealth;
    }

    @GetMapping({"/healthz", "/health/live"})
    public KfeHealthSnapshot live() {
        return new KfeHealthSnapshot("UP", "kfe-service", Instant.now(), Map.of());
    }

    @GetMapping({"/health/ready", "/health/dependencies"})
    public ResponseEntity<KfeHealthSnapshot> ready() {
        DependencyStatus database = databaseStatus();
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("database", database.status());

        // Add rail health: readiness requires at least one custody or Lightning rail live
        KfeFinancialRailHealthAdapter rails = railHealth.getIfAvailable();
        boolean anyRailLive = false;
        if (rails != null) {
            var custody = rails.custodyProvider();
            if (custody != null) {
                String custodyStatus = custody.live() ? "UP" : "DOWN";
                dependencies.put("custody-" + custody.providerName().toLowerCase(), custodyStatus);
                if (custody.live()) anyRailLive = true;
            }
            var activeRails = rails.activeRailProviders();
            for (var entry : activeRails.entrySet()) {
                String railStatus = entry.getValue().live() ? "UP" : "DOWN";
                dependencies.put("rail-" + entry.getKey(), railStatus);
                if (entry.getValue().live()) anyRailLive = true;
            }
        }

        // Ready if DB is up and at least one financial rail is live (or no rails configured)
        boolean railOk = rails == null || anyRailLive;
        boolean up = database.up() && railOk;
        HttpStatus status = up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new KfeHealthSnapshot(
                up ? "UP" : "DOWN",
                "kfe-service",
                Instant.now(),
                Map.copyOf(dependencies)));
    }

    private DependencyStatus databaseStatus() {
        DataSource availableDataSource = dataSource.getIfAvailable();
        if (availableDataSource == null) {
            return new DependencyStatus(true, "not-configured");
        }
        try (Connection connection = availableDataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return new DependencyStatus(valid, valid ? "UP" : "DOWN");
        } catch (Exception exception) {
            return new DependencyStatus(false, "DOWN");
        }
    }

    public record KfeHealthSnapshot(
            String status,
            String service,
            Instant checkedAt,
            Map<String, String> dependencies) {
    }

    private record DependencyStatus(boolean up, String status) {
    }
}
