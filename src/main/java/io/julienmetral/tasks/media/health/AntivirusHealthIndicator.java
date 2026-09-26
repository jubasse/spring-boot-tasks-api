package io.julienmetral.tasks.media.health;

import io.julienmetral.tasks.config.AntivirusProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Up when clamd answers its PING, or when scanning is disabled; reported as {@code antivirus}. While it is down,
 * uploads are refused with 503, but the rest of the API works, which is why readiness does not include it.
 */
@Component
@RequiredArgsConstructor
public class AntivirusHealthIndicator extends AbstractHealthIndicator {

    private static final byte[] PING = "zPING\0".getBytes(StandardCharsets.US_ASCII);

    private static final long PING_TIMEOUT_MILLIS = 5_000;

    private final AntivirusProperties properties;

    @Override
    protected void doHealthCheck(Health.Builder builder) throws IOException {
        if (!properties.enabled()) {
            builder.up().withDetail("scanning", "disabled");
            return;
        }

        // The scan timeout (a minute) suits large uploads, not a probe that must answer quickly
        int timeoutMillis = Math.toIntExact(Math.min(properties.timeout().toMillis(), PING_TIMEOUT_MILLIS));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.host(), properties.port()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write(PING);

            String reply = readReply(socket.getInputStream());

            if ("PONG".equals(reply)) {
                builder.up();
            } else {
                builder.down().withDetail("reply", reply);
            }
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int next;

        while ((next = in.read()) != -1 && next != 0) {
            reply.write(next);
        }

        return reply.toString(StandardCharsets.US_ASCII).trim();
    }
}
