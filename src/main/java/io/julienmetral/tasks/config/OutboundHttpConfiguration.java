package io.julienmetral.tasks.config;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of every HTTP client Spring Boot builds, {@code RestClient} and HTTP service clients included. The AWS SDK
 * builds its own client and is not affected.
 */
@Configuration
@EnableConfigurationProperties(OutboundHttpProperties.class)
public class OutboundHttpConfiguration {

    /**
     * Only public addresses, plus {@code outbound-http.allowed-addresses}: users supply webhook URLs, and a private
     * address would let them make the API call its own network or the cloud metadata service (SSRF). With Apache
     * HttpClient, the filter checks the addresses a host resolves to and the client connects to exactly those, so a
     * DNS answer that changes between the check and the connection cannot get around it.
     */
    @Bean
    InetAddressFilter outboundAddressFilter(OutboundHttpProperties properties) {
        InetAddressFilter publicAddresses = InetAddressFilter.externalAddresses();

        return properties.allowedAddresses().isEmpty()
                ? publicAddresses
                : publicAddresses.or(properties.allowedAddresses().toArray(String[]::new));
    }

    // Warning: Apache HttpClient's default retry strategy sends a POST again when the server answers 429 or 503, after
    // sleeping for its Retry-After, however long. Retrying is the caller's decision.
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> noAutomaticRetries() {
        return builder -> builder.withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries);
    }
}
