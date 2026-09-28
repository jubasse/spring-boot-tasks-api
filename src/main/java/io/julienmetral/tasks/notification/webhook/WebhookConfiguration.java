package io.julienmetral.tasks.notification.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
@ImportHttpServices(group = WebhookConfiguration.CLIENT_GROUP, types = WebhookClient.class)
public class WebhookConfiguration {

    // Its timeouts, redirects and cookies are set under spring.http.serviceclient.webhooks
    static final String CLIENT_GROUP = "webhooks";

    @Bean
    WebhookSecrets webhookSecrets(WebhookProperties properties) {
        return new WebhookSecrets(properties.encryptionKey());
    }

    @Bean
    WebhookSigner webhookSigner() {
        return new WebhookSigner();
    }

    // Every status is a result to record, so none raises an exception
    @Bean
    RestClientHttpServiceGroupConfigurer webhookClientGroup() {
        return groups -> groups.filterByName(CLIENT_GROUP).forEachClient((group, builder) -> builder
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .observationConvention(new WebhookObservationConvention()));
    }

    @Bean
    WebhookUrlPolicy webhookUrlPolicy(InetAddressFilter outboundAddressFilter, WebhookProperties properties) {
        return new WebhookUrlPolicy(outboundAddressFilter, properties.requireHttps());
    }
}
