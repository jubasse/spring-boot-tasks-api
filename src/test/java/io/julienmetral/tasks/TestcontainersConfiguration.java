package io.julienmetral.tasks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final int MAILPIT_SMTP_PORT = 1025;

	static final int MAILPIT_API_PORT = 8025;

	static final int RUSTFS_S3_PORT = 9000;

	static final int CLAMAV_PORT = 3310;

	static final String RUSTFS_ACCESS_KEY = "test-access-key";

	static final String RUSTFS_SECRET_KEY = "test-secret-key";

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

	// S3-compatible storage for media files; the bucket is created on startup (storage.rustfs.create-bucket)
	@Bean
	GenericContainer<?> rustfsContainer() {
		return new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.0"))
				.withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
				.withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
				.withExposedPorts(RUSTFS_S3_PORT)
				.waitingFor(Wait.forHttp("/health").forPort(RUSTFS_S3_PORT));
	}

	@Bean
	DynamicPropertyRegistrar rustfsProperties(GenericContainer<?> rustfsContainer) {
		return registry -> {
			registry.add("storage.rustfs.endpoint", () -> "http://%s:%d".formatted(
					rustfsContainer.getHost(),
					rustfsContainer.getMappedPort(RUSTFS_S3_PORT)
			));
			registry.add("storage.rustfs.access-key", () -> RUSTFS_ACCESS_KEY);
			registry.add("storage.rustfs.secret-key", () -> RUSTFS_SECRET_KEY);
			registry.add("storage.driver", () -> "rustfs");
			registry.add("storage.bucket", () -> "tasks-media-test");
			registry.add("storage.rustfs.create-bucket", () -> "true");
		};
	}

	// Signatures are baked into the image; freshclam is off so tests never depend on the network
	@Bean
	GenericContainer<?> clamavContainer() {
		return new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.5.4-debian"))
				.withEnv("CLAMAV_NO_FRESHCLAMD", "true")
				.withExposedPorts(CLAMAV_PORT)
				.waitingFor(Wait.forLogMessage(".*socket found, clamd started.*", 1)
						.withStartupTimeout(Duration.ofMinutes(3)));
	}

	@Bean
	DynamicPropertyRegistrar clamavProperties(GenericContainer<?> clamavContainer) {
		return registry -> {
			registry.add("antivirus.enabled", () -> "true");
			registry.add("antivirus.host", clamavContainer::getHost);
			registry.add("antivirus.port", () -> clamavContainer.getMappedPort(CLAMAV_PORT));
		};
	}

}
