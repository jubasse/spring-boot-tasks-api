package io.julienmetral.tasks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final int MAILPIT_SMTP_PORT = 1025;

	static final int MAILPIT_API_PORT = 8025;

	static final int RUSTFS_S3_PORT = 9000;

	static final int CLAMAV_PORT = 3310;

	static final String RUSTFS_ACCESS_KEY = "test-access-key";

	static final String RUSTFS_SECRET_KEY = "test-secret-key";

	/** Marks the reusable containers, so that they can be removed without touching any other container. */
	public static final String REUSABLE_LABEL = "io.julienmetral.tasks.test-container";

	/** The threat name clamd reports for the EICAR test file with the signature database below. */
	public static final String EICAR_THREAT = "Eicar-Test-Signature.UNOFFICIAL";

	// A body signature matches the EICAR string anywhere, including inside archives. ClamAV suffixes the name of a
	// signature from an unsigned database with ".UNOFFICIAL".
	private static final String EICAR_SIGNATURE = "Eicar-Test-Signature:0:*:" + HexFormat.of().formatHex(
			"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII)
	) + "\n";

	// Postgres, Mailpit, RustFS and ClamAV are reused when testcontainers.reuse.enable is set (see the README): one of
	// each serves every test context and every run, instead of one set per cached context. They must stay identical
	// across contexts for that, so a context cannot customise them.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
				.withLabel(REUSABLE_LABEL, "true")
				.withReuse(true);
	}

	// Not reused: the dead-letter tests make the listeners' collaborators fail, and on a shared broker those listeners
	// would consume, and dead-letter, the messages of every other context
	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.2.9-management"));
	}

	// SMTP server that catches every email; tests read them through its HTTP API (see support.Mailpit)
	@Bean
	GenericContainer<?> mailpitContainer() {
		return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.2"))
				.withExposedPorts(MAILPIT_SMTP_PORT, MAILPIT_API_PORT)
				.waitingFor(Wait.forHttp("/livez").forPort(MAILPIT_API_PORT))
				.withLabel(REUSABLE_LABEL, "true")
				.withReuse(true);
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
				.waitingFor(Wait.forHttp("/health").forPort(RUSTFS_S3_PORT))
				.withLabel(REUSABLE_LABEL, "true")
				.withReuse(true);
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

	// Loads only the EICAR signature: with the signatures baked into the image, every clamd took about 1 GB, one per
	// Spring test context, and a full run exhausted the machine's memory. freshclam is off, so tests never depend on
	// the network.
	@Bean
	GenericContainer<?> clamavContainer() {
		return new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.5.4-debian"))
				.withEnv("CLAMAV_NO_FRESHCLAMD", "true")
				.withEnv("CLAMD_CONF_DatabaseDirectory", "/var/lib/clamav-test")
				.withCopyToContainer(Transferable.of(EICAR_SIGNATURE), "/var/lib/clamav-test/eicar.ndb")
				.withExposedPorts(CLAMAV_PORT)
				.waitingFor(Wait.forLogMessage(".*socket found, clamd started.*", 1)
						.withStartupTimeout(Duration.ofMinutes(3)))
				.withLabel(REUSABLE_LABEL, "true")
				.withReuse(true);
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
