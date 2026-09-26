package io.julienmetral.tasks.config;

import io.julienmetral.tasks.media.services.ObjectStorage;
import io.julienmetral.tasks.media.services.S3ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Amazon S3: endpoint derived from the region, credentials from the AWS default chain (environment, IAM role, ...).
 * The bucket is provisioned by the infrastructure, never created by the application.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, MediaProperties.class})
@ConditionalOnProperty(name = "storage.driver", havingValue = "aws-s3")
public class AwsS3StorageConfiguration {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client
                .builder()
                .region(region(properties))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner
                .builder()
                .region(region(properties))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        return new S3ObjectStorage(s3Client, s3Presigner, properties.bucket(), properties.presignedUrlTtl());
    }

    private static Region region(StorageProperties properties) {
        Assert.notNull(properties.awsS3(), "storage.aws-s3 must be set when storage.driver is aws-s3");
        Assert.hasText(properties.awsS3().region(), "storage.aws-s3.region must be set");

        return Region.of(properties.awsS3().region());
    }
}
