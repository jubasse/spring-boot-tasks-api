package io.julienmetral.tasks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final int MAILPIT_SMTP_PORT = 1025;

	static final int MAILPIT_API_PORT = 8025;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}

	// SMTP server that catches every email; tests read them through its HTTP API (see support.Mailpit)
	@Bean
	GenericContainer<?> mailpitContainer() {
		return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.2"))
				.withExposedPorts(MAILPIT_SMTP_PORT, MAILPIT_API_PORT)
				.waitingFor(Wait.forHttp("/livez").forPort(MAILPIT_API_PORT));
	}

	@Bean
	DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
		return registry -> {
			registry.add("spring.mail.host", mailpitContainer::getHost);
			registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(MAILPIT_SMTP_PORT));
			registry.add("mailpit.api-url", () -> "http://%s:%d".formatted(
					mailpitContainer.getHost(),
					mailpitContainer.getMappedPort(MAILPIT_API_PORT)
			));
		};
	}

}
