package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.MediaDownload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Serves both S3 drivers (rustfs, aws-s3): they differ only in how the client is configured. */
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final Duration presignedUrlTtl;

    @Override
    public void put(String key, InputStream content, long length, String contentType) {
        try {
            s3Client.putObject(
                    request -> request
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .contentLength(length),
                    RequestBody.fromInputStream(content, length)
            );
        } catch (SdkException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return s3Client.getObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    @Override
    public List<String> listKeysModifiedBefore(Instant cutoff) {
        try {
            return s3Client
                    .listObjectsV2Paginator(request -> request.bucket(bucket))
                    .contents()
                    .stream()
                    .filter(object -> object.lastModified().isBefore(cutoff))
                    .map(S3Object::key)
                    .toList();
        } catch (SdkException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    /**
     * Signed locally, without calling the storage. The response headers are part of the signature, so the browser
     * downloads the file under its original name and never renders it inline.
     */
    @Override
    public MediaDownload presignDownload(String key, String filename, String contentType) {
        String contentDisposition = ContentDisposition
                .attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();

        var presigned = s3Presigner.presignGetObject(request -> request
                .signatureDuration(presignedUrlTtl)
                .getObjectRequest(get -> get
                        .bucket(bucket)
                        .key(key)
                        .responseContentType(contentType)
                        .responseContentDisposition(contentDisposition)));

        return new MediaDownload(presigned.url(), presigned.expiration());
    }
}
