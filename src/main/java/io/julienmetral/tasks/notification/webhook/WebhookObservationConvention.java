package io.julienmetral.tasks.notification.webhook;

import io.micrometer.common.KeyValue;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.HighCardinalityKeyNames;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * The metrics and traces of webhook calls, without their destination. The default convention tags metrics with the
 * host, one series per subscriber host, and puts the full URL in traces, where a Slack URL would expose its secret.
 */
class WebhookObservationConvention extends DefaultClientRequestObservationConvention {

    private static final String CLIENT_NAME = "webhook";

    @Override
    protected KeyValue clientName(ClientRequestObservationContext context) {
        return KeyValue.of(LowCardinalityKeyNames.CLIENT_NAME, CLIENT_NAME);
    }

    @Override
    protected KeyValue requestUri(ClientRequestObservationContext context) {
        return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, CLIENT_NAME);
    }
}
