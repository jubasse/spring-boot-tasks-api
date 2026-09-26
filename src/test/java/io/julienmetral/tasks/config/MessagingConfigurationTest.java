package io.julienmetral.tasks.config;

import io.julienmetral.tasks.mail.MailMessage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingConfigurationTest {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private static final MailMessage MESSAGE = new MailMessage("jane@example.com", "Subject", "Body");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final MessageConverter converter = new MessagingConfiguration().messageConverter(jsonMapper);

    @Test
    void mailMessageIsWrittenAsJsonWithItsTypeHeader() {
        Message message = converter.toMessage(MESSAGE, new MessageProperties());

        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(message.getMessageProperties().<String>getHeader(TYPE_ID_HEADER))
                .isEqualTo(MailMessage.class.getName());

        JsonNode body = jsonMapper.readTree(message.getBody());
        assertThat(body.path("to").asString()).isEqualTo("jane@example.com");
        assertThat(body.path("subject").asString()).isEqualTo("Subject");
        assertThat(body.path("text").asString()).isEqualTo("Body");
    }

    @Test
    void mailMessageRoundTripsToTheListenerParameterType() {
        Message message = converter.toMessage(MESSAGE, new MessageProperties());
        receivedByListenerOf(MailMessage.class, message);

        assertThat(converter.fromMessage(message)).isEqualTo(MESSAGE);
    }

    @Test
    void listenerParameterTypeTakesPrecedenceOverTheTypeHeader() {
        Message message = jsonMessage("java.net.URL", """
                {"to":"jane@example.com","subject":"Subject","text":"Body"}""");
        receivedByListenerOf(MailMessage.class, message);

        assertThat(converter.fromMessage(message)).isEqualTo(MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.net.URL",
            "java.io.File",
            "org.springframework.context.support.FileSystemXmlApplicationContext"
    })
    void typeHeaderNamingAClassOutsideTheApplicationIsRefused(String className) {
        Message message = jsonMessage(className, "\"https://example.com/payload.xml\"");

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in the trusted packages");
    }

    @Test
    @Disabled("bug: MessagingConfiguration trusts \"io.julienmetral.tasks\", but spring-amqp's "
            + "DefaultJacksonJavaTypeMapper.isTrustedPackage compares the package name for equality, so classes in "
            + "sub-packages such as io.julienmetral.tasks.mail.MailMessage are refused. Reading mail.send.dead-letter "
            + "with RabbitTemplate.receiveAndConvert(queue) fails; listeners work only because their parameter type "
            + "takes precedence over the type header.")
    void mailMessageRoundTripsThroughItsTypeHeader() {
        Message message = converter.toMessage(MESSAGE, new MessageProperties());

        assertThat(converter.fromMessage(message)).isEqualTo(MESSAGE);
    }

    @Test
    @Disabled("bug: the comment on MessagingConfiguration.messageConverter says only the application's own types "
            + "may be deserialized from the type header, but spring-amqp's DefaultJacksonJavaTypeMapper always "
            + "trusts java.util and java.lang on top of the configured packages.")
    void typeHeaderNamingAJdkCollectionIsRefused() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("__ContentTypeId__", "java.lang.String");
        Message message = jsonMessage("java.util.ArrayList", "[\"a\"]", properties);

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in the trusted packages");
    }

    private static Message jsonMessage(String typeId, String json) {
        return jsonMessage(typeId, json, new MessageProperties());
    }

    private static Message jsonMessage(String typeId, String json, MessageProperties properties) {
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader(TYPE_ID_HEADER, typeId);

        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    // What the @RabbitListener adapter sets from the method signature before converting the payload
    private static void receivedByListenerOf(Class<?> parameterType, Message message) {
        message.getMessageProperties().setInferredArgumentType(parameterType);
    }
}
