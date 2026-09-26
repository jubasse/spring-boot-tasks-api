package io.julienmetral.tasks.media.health;

import io.julienmetral.tasks.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageHealthIndicatorTest {

    private static final StorageProperties PROPERTIES =
            new StorageProperties("rustfs", "tasks-media", Duration.ofMinutes(10), null, null);

    @Mock
    private S3Client s3Client;

    @Captor
    private ArgumentCaptor<Consumer<HeadBucketRequest.Builder>> headBucketRequest;

    @Test
    void answeringBucketIsUpWithTheDriverAndTheBucket() {
        Health health = new StorageHealthIndicator(s3Client, PROPERTIES).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEqualTo(Map.of("driver", "rustfs", "bucket", "tasks-media"));
    }

    @Test
    void probeAsksForTheConfiguredBucket() {
        new StorageHealthIndicator(s3Client, PROPERTIES).health();

        verify(s3Client).headBucket(headBucketRequest.capture());
        HeadBucketRequest.Builder request = HeadBucketRequest.builder();
        headBucketRequest.getValue().accept(request);
        assertThat(request.build().bucket()).isEqualTo("tasks-media");
    }

    @Test
    void unreachableStorageIsDownWithTheError() {
        when(s3Client.headBucket(any(Consumer.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        Health health = new StorageHealthIndicator(s3Client, PROPERTIES).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error");
        assertThat(health.getDetails().get("error").toString())
                .contains(SdkClientException.class.getName(), "Connection refused");
    }

    @Test
    void missingBucketIsDownWithTheError() {
        when(s3Client.headBucket(any(Consumer.class)))
                .thenThrow(NoSuchBucketException.builder().message("The specified bucket does not exist").build());

        Health health = new StorageHealthIndicator(s3Client, PROPERTIES).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error");
        assertThat(health.getDetails().get("error").toString())
                .contains(NoSuchBucketException.class.getName(), "The specified bucket does not exist");
    }
}
