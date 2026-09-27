package io.julienmetral.tasks.notification.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfiguration {

    @Bean
    WebhookSecrets webhookSecrets(WebhookProperties properties) {
        return new WebhookSecrets(properties.encryptionKey());
    }

    @Bean
    WebhookUrlPolicy webhookUrlPolicy(InetAddressFilter outboundAddressFilter, WebhookProperties properties) {
        return new WebhookUrlPolicy(outboundAddressFilter, properties.requireHttps());
    }
}
