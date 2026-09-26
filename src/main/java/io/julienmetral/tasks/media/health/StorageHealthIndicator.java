package io.julienmetral.tasks.media.health;

import io.julienmetral.tasks.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/** Up when the media bucket answers; reported as {@code storage}. */
@Component
@RequiredArgsConstructor
public class StorageHealthIndicator extends AbstractHealthIndicator {

    private final S3Client s3Client;
    private final StorageProperties properties;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        s3Client.headBucket(request -> request.bucket(properties.bucket()));

        builder.up()
                .withDetail("driver", properties.driver())
                .withDetail("bucket", properties.bucket());
    }
}
