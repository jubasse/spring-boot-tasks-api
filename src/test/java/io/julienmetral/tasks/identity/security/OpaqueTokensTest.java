package io.julienmetral.tasks.identity.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokensTest {

    @Test
    void generateReturnsUrlSafeUnpaddedTokenOf256Bits() {
        String token = OpaqueTokens.generate();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void generateReturnsDistinctTokens() {
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            tokens.add(OpaqueTokens.generate());
        }

        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hashReturnsLowercaseHexSha256() {
        // Known SHA-256 test vector
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsDeterministicAnd64Characters() {
        String token = OpaqueTokens.generate();

        assertThat(OpaqueTokens.hash(token))
                .hasSize(64)
                .matches("[0-9a-f]+")
                .isEqualTo(OpaqueTokens.hash(token))
                .isNotEqualTo(token);
    }

    @Test
    void hashDiffersForDifferentTokens() {
        assertThat(OpaqueTokens.hash("token-a")).isNotEqualTo(OpaqueTokens.hash("token-b"));
    }

    @Test
    void hashEncodesInputAsUtf8() {
        // SHA-256 of the UTF-8 bytes of "\u00e9" (0xC3 0xA9), not of its ISO-8859-1 byte
        assertThat(OpaqueTokens.hash("\u00e9"))
                .isEqualTo("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c");
    }
}
