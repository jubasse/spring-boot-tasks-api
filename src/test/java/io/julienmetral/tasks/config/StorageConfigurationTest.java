package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.services.ObjectStorage;
import io.julienmetral.tasks.media.services.S3ObjectStorage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.captor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageConfigurationTest {

    private static final String[] COMMON = {
            "storage.bucket=tasks-media",
            "storage.presigned-url-ttl=10m",
            "media.avatar-max-size=5MB",
            "media.attachment-max-size=25MB"
    };

    private static final String[] RUSTFS = {
            "storage.driver=rustfs",
            "storage.rustfs.endpoint=http://rustfs:9000",
            "storage.rustfs.access-key=test-access-key",
            "storage.rustfs.secret-key=test-secret-key"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class, RustFsStorageConfiguration.class, AwsS3StorageConfiguration.class)
            .withPropertyValues(COMMON);

    @Nested
    class RustFs {

        private final ApplicationContextRunner rustfs = runner.withPropertyValues(RUSTFS);

        @Test
        void buildsPathStyleClientsAgainstTheConfiguredEndpoint() {
            rustfs.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ObjectStorage.class);
                assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(S3ObjectStorage.class);
                assertThat(context).doesNotHaveBean(AwsS3StorageConfiguration.class);

                S3Client client = context.getBean(S3Client.class);
                assertThat(client.serviceClientConfiguration().endpointOverride())
                        .contains(URI.create("http://rustfs:9000"));

                MediaDownload download = context.getBean(ObjectStorage.class)
                        .presignDownload("avatar/key", "me.png", "image/png");
                assertThat(download.url().toString()).startsWith("http://rustfs:9000/tasks-media/avatar/key?");
                assertThat(download.url().getQuery()).contains("X-Amz-Expires=600");
            });
        }

        @Test
        void bindsBothPropertyRecords() {
            rustfs.run(context -> {
                StorageProperties storage = context.getBean(StorageProperties.class);
                assertThat(storage.driver()).isEqualTo("rustfs");
                assertThat(storage.bucket()).isEqualTo("tasks-media");
                assertThat(storage.presignedUrlTtl()).isEqualTo(Duration.ofMinutes(10));
                assertThat(storage.rustfs().createBucket()).isFalse();

                MediaProperties media = context.getBean(MediaProperties.class);
                assertThat(media.avatarMaxSize()).isEqualTo(DataSize.ofMegabytes(5));
                assertThat(media.attachmentMaxSize()).isEqualTo(DataSize.ofMegabytes(25));
            });
        }

        @Test
        void presignedUrlsUseThePublicEndpointWhenSet() {
            rustfs.withPropertyValues("storage.rustfs.public-endpoint=https://files.example.com").run(context -> {
                assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride())
                        .contains(URI.create("http://rustfs:9000"));

                MediaDownload download = context.getBean(ObjectStorage.class)
                        .presignDownload("avatar/key", "me.png", "image/png");
                assertThat(download.url().toString())
                        .startsWith("https://files.example.com/tasks-media/avatar/key?");
            });
        }

        @Test
        void blankPublicEndpointFallsBackToTheEndpoint() {
            rustfs.withPropertyValues("storage.rustfs.public-endpoint= ").run(context -> {
                MediaDownload download = context.getBean(ObjectStorage.class)
                        .presignDownload("avatar/key", "me.png", "image/png");
                assertThat(download.url().toString()).startsWith("http://rustfs:9000/");
            });
        }

        @Test
        void missingSectionFailsStartup() {
            runner.withPropertyValues("storage.driver=rustfs").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.rustfs must be set when storage.driver is rustfs"));
        }

        @Test
        void missingEndpointFailsStartup() {
            rustfs.withPropertyValues("storage.rustfs.endpoint=").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.rustfs.endpoint must be set"));
        }

        @Test
        void missingAccessKeyFailsStartup() {
            rustfs.withPropertyValues("storage.rustfs.access-key=").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.rustfs.access-key must be set"));
        }

        @Test
        void missingSecretKeyFailsStartup() {
            rustfs.withPropertyValues("storage.rustfs.secret-key=").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.rustfs.secret-key must be set"));
        }

        @Test
        void registersTheBucketInitializerOnlyWhenAsked() {
            rustfs.run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
            rustfs.withPropertyValues("storage.rustfs.create-bucket=false")
                    .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
            rustfs.withPropertyValues("storage.rustfs.create-bucket=true")
                    .run(context -> assertThat(context).hasSingleBean(ApplicationRunner.class));
        }
    }

    @Nested
    class BucketInitializer {

        private final S3Client s3Client = mock(S3Client.class);

        private final StorageProperties properties = new StorageProperties(
                "rustfs",
                "tasks-media",
                Duration.ofMinutes(10),
                new StorageProperties.RustFs("http://rustfs:9000", null, "key", "secret", true),
                null
        );

        private void runInitializer() throws Exception {
            new RustFsStorageConfiguration()
                    .storageBucketInitializer(s3Client, properties)
                    .run(new DefaultApplicationArguments());
        }

        @Test
        @SuppressWarnings("unchecked")
        void createsAMissingBucket() throws Exception {
            when(s3Client.headBucket(any(Consumer.class))).thenThrow(NoSuchBucketException.builder().build());

            runInitializer();

            ArgumentCaptor<Consumer<HeadBucketRequest.Builder>> head = captor();
            verify(s3Client).headBucket(head.capture());
            HeadBucketRequest.Builder headRequest = HeadBucketRequest.builder();
            head.getValue().accept(headRequest);
            assertThat(headRequest.build().bucket()).isEqualTo("tasks-media");

            ArgumentCaptor<Consumer<CreateBucketRequest.Builder>> create = captor();
            verify(s3Client).createBucket(create.capture());
            CreateBucketRequest.Builder createRequest = CreateBucketRequest.builder();
            create.getValue().accept(createRequest);
            assertThat(createRequest.build().bucket()).isEqualTo("tasks-media");
        }

        @Test
        @SuppressWarnings("unchecked")
        void leavesAnExistingBucketAlone() throws Exception {
            runInitializer();

            verify(s3Client).headBucket(any(Consumer.class));
            verify(s3Client, never()).createBucket(any(Consumer.class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void otherStorageErrorsFailStartupWithoutCreatingTheBucket() {
            S3Exception forbidden = (S3Exception) S3Exception.builder().statusCode(403).message("Forbidden").build();
            when(s3Client.headBucket(any(Consumer.class))).thenThrow(forbidden);

            assertThatThrownBy(this::runInitializer).isSameAs(forbidden);

            verify(s3Client, never()).createBucket(any(Consumer.class));
        }
    }

    @Nested
    class AwsS3 {

        private final ApplicationContextRunner awsS3 = runner.withPropertyValues(
                "storage.driver=aws-s3",
                "storage.aws-s3.region=eu-west-3"
        );

        @Test
        void buildsClientsForTheRegionWithoutCallingAws() {
            awsS3.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(S3Client.class);
                assertThat(context).hasSingleBean(S3Presigner.class);
                assertThat(context).hasSingleBean(ObjectStorage.class);
                assertThat(context).doesNotHaveBean(RustFsStorageConfiguration.class);
                assertThat(context).doesNotHaveBean(ApplicationRunner.class);

                S3Client client = context.getBean(S3Client.class);
                assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("eu-west-3");
                assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
            });
        }

        @Test
        void neverCreatesTheBucketEvenWhenTheRustFsFlagIsSet() {
            awsS3.withPropertyValues("storage.rustfs.create-bucket=true")
                    .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
        }

        @Test
        void missingSectionFailsStartup() {
            runner.withPropertyValues("storage.driver=aws-s3").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.aws-s3 must be set when storage.driver is aws-s3"));
        }

        @Test
        void missingRegionFailsStartup() {
            awsS3.withPropertyValues("storage.aws-s3.region=").run(context ->
                    assertThat(context).getFailure()
                            .rootCause()
                            .hasMessage("storage.aws-s3.region must be set"));
        }
    }

    @Nested
    class Validation {

        @Test
        void blankBucketFailsStartup() {
            runner.withPropertyValues(RUSTFS).withPropertyValues("storage.bucket= ").run(context ->
                    assertThat(context).getFailure().hasStackTraceContaining("on field 'bucket'"));
        }

        @Test
        void missingPresignedUrlTtlFailsStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class, RustFsStorageConfiguration.class)
                    .withPropertyValues(RUSTFS)
                    .withPropertyValues("storage.bucket=tasks-media", "media.avatar-max-size=5MB",
                            "media.attachment-max-size=25MB")
                    .run(context -> assertThat(context).getFailure()
                            .hasStackTraceContaining("on field 'presignedUrlTtl'"));
        }

        @Test
        void missingMediaSizeFailsStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class, RustFsStorageConfiguration.class)
                    .withPropertyValues(RUSTFS)
                    .withPropertyValues("storage.bucket=tasks-media", "storage.presigned-url-ttl=10m",
                            "media.avatar-max-size=5MB")
                    .run(context -> assertThat(context).getFailure()
                            .hasStackTraceContaining("on field 'attachmentMaxSize'"));
        }

        @Test
        void unknownDriverFailsStartupWithAValidationError() {
            runner.withPropertyValues("storage.driver=s3").run(context ->
                    assertThat(context).getFailure().hasStackTraceContaining("on field 'driver'"));
        }
    }
}
