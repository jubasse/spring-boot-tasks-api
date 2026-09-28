package io.julienmetral.tasks.notification.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecretsTest {

    private static final String KEY = key(32, 1);

    private final WebhookSecrets secrets = new WebhookSecrets(KEY);

    @Test
    void generatedSecretIsWhsecFollowedByTheBase64Of32Bytes() {
        String secret = secrets.generate();

        assertThat(secret).startsWith("whsec_").hasSize("whsec_".length() + 44);
        assertThat(Base64.getDecoder().decode(secret.substring("whsec_".length()))).hasSize(32);
    }

    @Test
    void generatedSecretsAreDistinct() {
        Set<String> generated = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            generated.add(secrets.generate());
        }

        assertThat(generated).hasSize(1000);
    }

    @Test
    void decryptReturnsTheEncryptedSecret() {
        String secret = secrets.generate();

        assertThat(secrets.decrypt(secrets.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void encryptedValueIsV1FollowedByBase64() {
        String encrypted = secrets.encrypt(secrets.generate());

        assertThat(encrypted).startsWith("v1:");
        assertThat(Base64.getDecoder().decode(encrypted.substring("v1:".length()))).isNotEmpty();
    }

    @Test
    void encryptedValueNeverContainsTheSecret() {
        String secret = secrets.generate();

        String encrypted = secrets.encrypt(secret);

        assertThat(encrypted)
                .doesNotContain(secret)
                .doesNotContain(secret.substring("whsec_".length()));
    }

    @Test
    void encryptingOneSecretTwiceGivesTwoValuesThatBothDecrypt() {
        String secret = secrets.generate();

        String first = secrets.encrypt(secret);
        String second = secrets.encrypt(secret);

        assertThat(first).isNotEqualTo(second);
        assertThat(secrets.decrypt(first)).isEqualTo(secret);
        assertThat(secrets.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void anotherInstanceWithTheSameKeyDecrypts() {
        String secret = secrets.generate();

        assertThat(new WebhookSecrets(KEY).decrypt(secrets.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        String encrypted = secrets.encrypt(secrets.generate());
        WebhookSecrets otherKey = new WebhookSecrets(key(32, 2));

        assertThatThrownBy(() -> otherKey.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyLongerThan32BytesIsAccepted() {
        WebhookSecrets longKey = new WebhookSecrets(key(64, 1));
        String secret = longKey.generate();

        assertThat(longKey.decrypt(longKey.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void keyOf31BytesIsRefusedWithHowToGenerateOne() {
        assertRefused(key(31, 1));
    }

    @Test
    void missingKeyIsRefusedWithHowToGenerateOne() {
        assertRefused(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void blankKeyIsRefusedWithHowToGenerateOne(String blank) {
        assertRefused(blank);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not base64 at all, but long enough to hold 32 bytes",
            "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    })
    void keyThatIsNotBase64IsRefusedWithHowToGenerateOne(String notBase64) {
        assertRefused(notBase64);
    }

    @Test
    void valueWithAnUnknownKeyVersionIsRefused() {
        String encrypted = secrets.encrypt(secrets.generate());
        String otherVersion = "v2:" + encrypted.substring("v1:".length());

        assertThatIllegalStateException()
                .isThrownBy(() -> secrets.decrypt(otherVersion))
                .withMessageContaining("unknown key version");
    }

    @Test
    void valueWithoutAKeyVersionIsRefused() {
        String withoutVersion = secrets.encrypt(secrets.generate()).substring("v1:".length());

        assertThatIllegalStateException()
                .isThrownBy(() -> secrets.decrypt(withoutVersion))
                .withMessageContaining("unknown key version");
    }

    @Test
    void tamperedCiphertextIsRefused() {
        String encrypted = secrets.encrypt(secrets.generate());
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
        bytes[bytes.length / 2] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> secrets.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncatedCiphertextIsRefused() {
        String encrypted = secrets.encrypt(secrets.generate());
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
        byte[] truncated = new byte[bytes.length - 1];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> secrets.decrypt("v1:" + Base64.getEncoder().encodeToString(truncated)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertRefused(String key) {
        assertThatIllegalStateException()
                .isThrownBy(() -> new WebhookSecrets(key))
                .withMessageContaining("WEBHOOK_ENCRYPTION_KEY")
                .withMessageContaining("openssl rand -base64 32");
    }

    private static String key(int bytes, int seed) {
        byte[] key = new byte[bytes];

        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + seed);
        }

        return Base64.getEncoder().encodeToString(key);
    }
}
