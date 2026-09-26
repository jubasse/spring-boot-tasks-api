package io.julienmetral.tasks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The database alone, for the JDBC and JPA test slices; {@link TestcontainersConfiguration} imports it for the full
 * application.
 * <p>
 * Warning: keep a single definition of this container. Testcontainers reuses a container only when its whole
 * configuration matches, so a slice declaring its own Postgres, even with another label, would start a second one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
				.withLabel(TestcontainersConfiguration.REUSABLE_LABEL, "true")
				.withReuse(true);
	}

}
