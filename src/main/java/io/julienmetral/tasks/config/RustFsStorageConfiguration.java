package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.services.ObjectStorage;
import io.julienmetral.tasks.media.services.S3ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** Self-hosted S3 (RustFS in development and tests): explicit endpoint, static keys, path-style URLs. */
@Slf4j
@Configuration
@EnableConfigurationProperties({StorageProperties.class, MediaProperties.class})
@ConditionalOnProperty(name = "storage.driver", havingValue = "rustfs")
public class RustFsStorageConfiguration {

    // Any region signs requests correctly: RustFS ignores it
    private static final Region REGION = Region.US_EAST_1;

    @Bean
    S3Client s3Client(StorageProperties properties) {
        StorageProperties.RustFs rustfs = settings(properties);

        return S3Client
                .builder()
                .region(REGION)
                .endpointOverride(URI.create(rustfs.endpoint()))
                .credentialsProvider(credentials(rustfs))
                .serviceConfiguration(pathStyle())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        StorageProperties.RustFs rustfs = settings(properties);

        String endpoint = StringUtils.hasText(rustfs.publicEndpoint())
                ? rustfs.publicEndpoint()
                : rustfs.endpoint();

        return S3Presigner
                .builder()
                .region(REGION)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials(rustfs))
                .serviceConfiguration(pathStyle())
                .build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        return new S3ObjectStorage(s3Client, s3Presigner, properties.bucket(), properties.presignedUrlTtl());
    }

    @Bean
    @ConditionalOnBooleanProperty("storage.rustfs.create-bucket")
    ApplicationRunner storageBucketInitializer(S3Client s3Client, StorageProperties properties) {
        return args -> {
            try {
                s3Client.headBucket(request -> request.bucket(properties.bucket()));
            } catch (NoSuchBucketException exception) {
                s3Client.createBucket(request -> request.bucket(properties.bucket()));

                log.info("Created storage bucket {}", properties.bucket());
            }
        };
    }

    private static StorageProperties.RustFs settings(StorageProperties properties) {
        StorageProperties.RustFs rustfs = properties.rustfs();

        Assert.notNull(rustfs, "storage.rustfs must be set when storage.driver is rustfs");
        Assert.hasText(rustfs.endpoint(), "storage.rustfs.endpoint must be set");
        Assert.hasText(rustfs.accessKey(), "storage.rustfs.access-key must be set");
        Assert.hasText(rustfs.secretKey(), "storage.rustfs.secret-key must be set");

        return rustfs;
    }

    private static StaticCredentialsProvider credentials(StorageProperties.RustFs rustfs) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(rustfs.accessKey(), rustfs.secretKey()));
    }

    // Self-hosted servers serve buckets under /bucket, not as a bucket.host subdomain
    private static S3Configuration pathStyle() {
        return S3Configuration
                .builder()
                .pathStyleAccessEnabled(true)
                .build();
    }
}
