package io.julienmetral.tasks.config;

import io.julienmetral.tasks.identity.messaging.AvatarUploaded;
import io.julienmetral.tasks.mail.MailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingConfigurationTest {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private static final MailMessage MESSAGE = new MailMessage("jane@example.com", "Subject", "Body");

    private static final AvatarUploaded AVATAR_UPLOADED = new AvatarUploaded(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-00000000000a")
    );

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
    void mailMessageRoundTripsThroughItsTypeHeader() {
        Message message = converter.toMessage(MESSAGE, new MessageProperties());

        assertThat(converter.fromMessage(message)).isEqualTo(MESSAGE);
    }

    @Test
    void avatarUploadedIsWrittenAsJsonWithItsTypeHeader() {
        Message message = converter.toMessage(AVATAR_UPLOADED, new MessageProperties());

        assertThat(message.getMessageProperties().<String>getHeader(TYPE_ID_HEADER))
                .isEqualTo(AvatarUploaded.class.getName());

        JsonNode body = jsonMapper.readTree(message.getBody());
        assertThat(body.path("userId").asString()).isEqualTo(AVATAR_UPLOADED.userId().toString());
        assertThat(body.path("uploadId").asString()).isEqualTo(AVATAR_UPLOADED.uploadId().toString());
    }

    @Test
    void avatarUploadedRoundTripsThroughItsTypeHeader() {
        Message message = converter.toMessage(AVATAR_UPLOADED, new MessageProperties());

        assertThat(converter.fromMessage(message)).isEqualTo(AVATAR_UPLOADED);
    }

    @Test
    void avatarUploadedRoundTripsToTheListenerParameterType() {
        Message message = converter.toMessage(AVATAR_UPLOADED, new MessageProperties());
        receivedByListenerOf(AvatarUploaded.class, message);

        assertThat(converter.fromMessage(message)).isEqualTo(AVATAR_UPLOADED);
    }

    @Test
    void typeHeaderNamingAnApplicationClassOutsideTheMessagePackagesIsRefused() {
        Message message = jsonMessage("io.julienmetral.tasks.identity.entities.User", "{}");

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
