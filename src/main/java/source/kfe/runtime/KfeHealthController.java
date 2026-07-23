package source.kfe.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHealthController {

    private final ObjectProvider<DataSource> dataSource;

    public KfeHealthController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping({"/healthz", "/health/live"})
    public KfeHealthSnapshot live() {
        return new KfeHealthSnapshot("UP", "kfe-service", Instant.now(), Map.of());
    }

    @GetMapping({"/health/ready", "/health/dependencies"})
    public ResponseEntity<KfeHealthSnapshot> ready() {
        DependencyStatus database = databaseStatus();
        HttpStatus status = database.up() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new KfeHealthSnapshot(
                database.up() ? "UP" : "DOWN",
                "kfe-service",
                Instant.now(),
                Map.of("database", database.status())));
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
