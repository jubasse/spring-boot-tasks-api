package io.julienmetral.tasks.config;

import org.springframework.web.accept.ApiVersionParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version is a whole major number, {@code v1} in a path or {@code 1} in the configuration, and has one spelling.
 * <p>
 * Warning: Spring's default parser skips leading non-digits and reads minor versions, so {@code /api/v1.0/},
 * {@code /api/v01/} and {@code /api/vv1/} were served as version 1 while the security rules, written for
 * {@code /api/v1/}, did not apply to them: a disabled account listed tasks through {@code /api/v1.0/tasks}.
 */
class PathVersionParser implements ApiVersionParser<Integer> {

    private static final Pattern VERSION = Pattern.compile("v?([1-9][0-9]{0,2})");

    @Override
    public Integer parseVersion(String version) {
        Matcher matcher = VERSION.matcher(version);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an API version: " + version);
        }

        return Integer.valueOf(matcher.group(1));
    }
}
