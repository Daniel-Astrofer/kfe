package source.kfe.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Imports the KFE runtime into a Spring Boot application only when the kfe profile is active.
 *
 * <p>The root Core application deliberately excludes source.kfe from its default component scan.
 * This auto-configuration is discovered from the kfe-service jar and re-enables KFE components,
 * entities and repositories for the dedicated KFE runtime during the staged split.</p>
 */
@AutoConfiguration
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "false", matchIfMissing = true)
@ComponentScan(basePackages = "source.kfe")
@EntityScan(basePackages = "source.kfe.model")
@EnableJpaRepositories(basePackages = "source.kfe.repository")
public class KfeServiceRuntimeConfiguration {
}
