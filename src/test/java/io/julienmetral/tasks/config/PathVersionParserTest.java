package io.julienmetral.tasks.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PathVersionParserTest {

    private final PathVersionParser parser = new PathVersionParser();

    @ParameterizedTest
    @CsvSource({"v1, 1", "1, 1", "v2, 2", "v12, 12", "v999, 999"})
    void wholeMajorVersionIsRead(String version, int expected) {
        assertThat(parser.parseVersion(version)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.0", "v1.0.0", "1.0", "v01", "01", "vv1", "V1", "v0", "0", "v", "", "v1a", "v-1", "v1000"})
    void anyOtherSpellingIsRefused(String version) {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parseVersion(version));
    }
}
