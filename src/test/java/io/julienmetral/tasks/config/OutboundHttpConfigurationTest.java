package io.julienmetral.tasks.config;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OutboundHttpConfigurationTest {

    private static final String WEBHOOK_PAYLOAD = "{\"event\":\"task.assigned\"}";

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .addLast(mainApplicationYaml()))
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    RestClientAutoConfiguration.class
            ))
            .withUserConfiguration(OutboundHttpConfiguration.class)
            .withPropertyValues(
                    "spring.http.clients.connect-timeout=2s",
                    "spring.http.clients.read-timeout=5s"
            );

    private final ApplicationContextRunner allowingLoopback =
            runner.withPropertyValues("outbound-http.allowed-addresses=127.0.0.1/32");

    private final MockWebServer server = new MockWebServer();

    // Started by hand rather than with @StartStop, which binds to whatever localhost resolves to first, possibly ::1
    @BeforeEach
    void startServer() throws IOException {
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void allowedLoopbackReceiverIsCalledThroughApacheHttpClient() {
        server.enqueue(new MockResponse.Builder().code(204).build());

        allowingLoopback.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class))
                    .isInstanceOf(HttpComponentsClientHttpRequestFactoryBuilder.class);

            ResponseEntity<Void> response = post(context, "http://127.0.0.1:{port}/webhooks", server.getPort());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getHeaders().get("User-Agent")).startsWith("Apache-HttpClient/");
            assertThat(request.getBody()).isNotNull();
            assertThat(request.getBody().utf8()).isEqualTo(WEBHOOK_PAYLOAD);
        });
    }

    @Test
    void shippedSettingsRefuseTheLoopbackReceiverBeforeAnyRequestReachesIt() {
        runner.run(context -> {
            assertThat(context.getBean(OutboundHttpProperties.class).allowedAddresses()).isEmpty();

            assertThatExceptionOfType(FilteredHostException.class)
                    .isThrownBy(() -> post(context, "http://127.0.0.1:{port}/webhooks", server.getPort()))
                    .satisfies(refusal -> assertThat(refusal.getHost()).isEqualTo("127.0.0.1"));
            assertThat(server.getRequestCount()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://100.100.100.200/latest/meta-data/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://100.64.0.1/",
            "http://[fe80::1]/",
            "http://[fc00::1]/",
            "http://127.0.0.1:{port}/",
            "http://[::1]:{port}/",
            "http://[::ffff:7f00:1]:{port}/",
            "http://0.0.0.0:{port}/",
            "http://[::]:{port}/",
            "http://localhost:{port}/"
    })
    void privateDestinationIsRefusedBeforeConnecting(String destination) {
        runner.run(context -> {
            assertThatExceptionOfType(FilteredHostException.class)
                    .isThrownBy(() -> post(context, destination, server.getPort()));
            assertThat(server.getRequestCount()).isZero();
        });
    }

    @Test
    void allowedRangeOpensNoOtherLocalAddress() {
        allowingLoopback.run(context -> {
            assertThatExceptionOfType(FilteredHostException.class)
                    .isThrownBy(() -> post(context, "http://127.0.0.2:{port}/", server.getPort()));
            assertThatExceptionOfType(FilteredHostException.class)
                    .isThrownBy(() -> post(context, "http://0.0.0.0:{port}/", server.getPort()));
            assertThat(server.getRequestCount()).isZero();
        });
    }

    @Test
    void redirectToAPrivateAddressIsRefused() {
        server.enqueue(new MockResponse.Builder()
                .code(307)
                .setHeader("Location", "http://169.254.169.254/latest/meta-data/")
                .build());

        allowingLoopback.run(context -> {
            assertThatExceptionOfType(FilteredHostException.class)
                    .isThrownBy(() -> post(context, "http://127.0.0.1:{port}/webhooks", server.getPort()))
                    .satisfies(refusal -> assertThat(refusal.getHost()).isEqualTo("169.254.169.254"));
            assertThat(server.getRequestCount()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"SERVICE_UNAVAILABLE", "TOO_MANY_REQUESTS"})
    void postAnsweredWithRetryAfterIsSentOnceAndFailsAtOnce(HttpStatus status) {
        MockResponse refusal = new MockResponse.Builder()
                .code(status.value())
                .setHeader("Retry-After", String.valueOf(RETRY_AFTER.toSeconds()))
                .build();
        server.enqueue(refusal);
        // A second answer for a retry, which would otherwise wait forever for one
        server.enqueue(refusal);

        allowingLoopback.run(context -> {
            long start = System.nanoTime();

            assertThatExceptionOfType(RestClientResponseException.class)
                    .isThrownBy(() -> post(context, "http://127.0.0.1:{port}/webhooks", server.getPort()))
                    .satisfies(failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(elapsed).isLessThan(RETRY_AFTER);
        });
    }

    @Test
    void severalAllowedRangesAreReadFromACommaSeparatedValue() {
        runner.withPropertyValues("outbound-http.allowed-addresses=127.0.0.1/32, 10.0.0.0/8").run(context -> {
            assertThat(context).hasNotFailed();
            InetAddressFilter filter = context.getBean(InetAddressFilter.class);

            assertThat(filter.matches(InetAddress.getByName("127.0.0.1"))).isTrue();
            assertThat(filter.matches(InetAddress.getByName("10.20.30.40"))).isTrue();
            assertThat(filter.matches(InetAddress.getByName("93.184.216.34"))).isTrue();
            assertThat(filter.matches(InetAddress.getByName("127.0.0.2"))).isFalse();
            assertThat(filter.matches(InetAddress.getByName("192.168.1.1"))).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "192.168.0.0/abc", "not-an-address", "localhost/32"})
    void invalidAllowedRangeFailsStartup(String range) {
        runner.withPropertyValues("outbound-http.allowed-addresses=" + range).run(context ->
                assertThat(context).getFailure()
                        .hasMessageContaining("outboundAddressFilter")
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700:4700::1111"})
    void publicAddressPassesTheFilter(String address) {
        runner.run(context -> assertThat(context.getBean(InetAddressFilter.class)
                .matches(InetAddress.getByName(address))).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "100.64.0.1", "fc00::1", "fd00:ec2::254", "127.0.0.1", "::1", "::ffff:127.0.0.1", "0.0.0.0", "::",
            "169.254.169.254", "fe80::1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "224.0.0.1", "64:ff9b::a9fe:a9fe"
    })
    void privateOrSpecialPurposeAddressDoesNotPassTheFilter(String address) {
        runner.run(context -> assertThat(context.getBean(InetAddressFilter.class)
                .matches(InetAddress.getByName(address))).isFalse());
    }

    private static ResponseEntity<Void> post(AssertableApplicationContext context, String uriTemplate, int port) {
        return context.getBean(RestClient.Builder.class).build()
                .post()
                .uri(uriTemplate, port)
                .contentType(MediaType.APPLICATION_JSON)
                .body(WEBHOOK_PAYLOAD)
                .retrieve()
                .toBodilessEntity();
    }

    private static PropertySource<?> mainApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("main application.yaml", new ClassPathResource("application.yaml"))
                    .getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
