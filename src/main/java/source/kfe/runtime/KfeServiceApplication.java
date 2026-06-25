package source.kfe.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "source.kfe")
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
@EntityScan(basePackages = "source.kfe.model")
@EnableJpaRepositories(basePackages = "source.kfe.repository")
public class KfeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KfeServiceApplication.class, args);
    }
}
