package io.julienmetral.tasks.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Object storage holding media files.
 *
 * @param driver          {@code rustfs} (development, self-hosted) or {@code aws-s3} (Amazon S3); selects the
 *                        configuration class that builds the storage
 * @param bucket          bucket holding every media object
 * @param presignedUrlTtl how long a download URL stays valid
 * @param rustfs          settings of the {@code rustfs} driver, ignored by the others
 * @param awsS3           settings of the {@code aws-s3} driver, ignored by the others
 */
@Validated
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        @NotNull @Pattern(regexp = "rustfs|aws-s3") String driver,
        @NotBlank String bucket,
        @NotNull Duration presignedUrlTtl,
        RustFs rustfs,
        AwsS3 awsS3
) {

    /**
     * @param endpoint       API endpoint the application talks to
     * @param publicEndpoint endpoint written into presigned URLs, when clients reach the storage through another
     *                       address than the application (Docker network, reverse proxy); blank means {@code endpoint}
     * @param accessKey      access key id
     * @param secretKey      secret access key
     * @param createBucket   create the bucket on startup when it is missing
     */
    public record RustFs(
            String endpoint,
            String publicEndpoint,
            String accessKey,
            String secretKey,
            boolean createBucket
    ) {
    }

    /**
     * Credentials come from the AWS default chain (environment variables, IAM role, ...), never from this file.
     *
     * @param region bucket region, e.g. {@code eu-west-3}
     */
    public record AwsS3(
            String region
    ) {
    }
}
