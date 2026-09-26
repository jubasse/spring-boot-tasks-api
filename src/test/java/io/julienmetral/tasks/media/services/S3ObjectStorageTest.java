package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.MediaDownload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {

    private static final String BUCKET = "tasks-media";

    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    private S3Client s3Client;

    @Captor
    private ArgumentCaptor<Consumer<PutObjectRequest.Builder>> putRequest;

    @Captor
    private ArgumentCaptor<RequestBody> putBody;

    @Captor
    private ArgumentCaptor<Consumer<DeleteObjectRequest.Builder>> deleteRequest;

    @Captor
    private ArgumentCaptor<ListObjectsV2Request> listRequests;

    @Captor
    private ArgumentCaptor<Consumer<GetObjectRequest.Builder>> getRequest;

    private S3Presigner presigner;

    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner
                .builder()
                .region(Region.EU_WEST_3)
                .endpointOverride(URI.create("http://storage.example:9000"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        storage = new S3ObjectStorage(s3Client, presigner, BUCKET, TTL);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void putSendsTheObjectWithItsTypeAndLength() throws IOException {
        byte[] content = "hello".getBytes(UTF_8);

        storage.put("task-attachment/key", new ByteArrayInputStream(content), content.length, "text/plain");

        verify(s3Client).putObject(putRequest.capture(), putBody.capture());
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        putRequest.getValue().accept(builder);
        PutObjectRequest request = builder.build();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo("task-attachment/key");
        assertThat(request.contentType()).isEqualTo("text/plain");
        assertThat(request.contentLength()).isEqualTo(content.length);

        RequestBody body = putBody.getValue();
        assertThat(body.optionalContentLength()).contains((long) content.length);
        try (InputStream stream = body.contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failingPutIsReportedAsStorageUnavailable() {
        SdkClientException failure = SdkClientException.create("Unable to connect");
        doThrow(failure).when(s3Client).putObject(any(Consumer.class), any(RequestBody.class));

        assertThatThrownBy(() -> storage.put("key", InputStream.nullInputStream(), 0, "text/plain"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessage("File storage is temporarily unavailable")
                .hasCause(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openStreamsTheObjectFromTheBucket() throws IOException {
        byte[] content = "photo".getBytes(UTF_8);
        when(s3Client.getObject(any(Consumer.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content))
        ));

        try (InputStream stream = storage.open("avatar-upload/key")) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }

        verify(s3Client).getObject(getRequest.capture());
        GetObjectRequest.Builder builder = GetObjectRequest.builder();
        getRequest.getValue().accept(builder);
        GetObjectRequest request = builder.build();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo("avatar-upload/key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failingOpenIsReportedAsStorageUnavailable() {
        SdkClientException failure = SdkClientException.create("Unable to connect");
        when(s3Client.getObject(any(Consumer.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.open("key"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessage("File storage is temporarily unavailable")
                .hasCause(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openingAMissingObjectIsReportedAsStorageUnavailable() {
        NoSuchKeyException failure = NoSuchKeyException.builder().statusCode(404).message("Not found").build();
        when(s3Client.getObject(any(Consumer.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.open("avatar-upload/gone"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCause(failure);
    }

    @Test
    void deleteRemovesTheObjectFromTheBucket() {
        storage.delete("task-attachment/key");

        verify(s3Client).deleteObject(deleteRequest.capture());
        DeleteObjectRequest.Builder builder = DeleteObjectRequest.builder();
        deleteRequest.getValue().accept(builder);
        DeleteObjectRequest request = builder.build();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo("task-attachment/key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failingDeleteIsReportedAsStorageUnavailable() {
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(500).message("Internal").build();
        doThrow(failure).when(s3Client).deleteObject(any(Consumer.class));

        assertThatThrownBy(() -> storage.delete("key"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCause(failure);
    }

    @Test
    void presignedDownloadPointsToTheObjectAndExpiresAfterTheTtl() {
        Instant before = Instant.now();

        MediaDownload download = storage.presignDownload("task-attachment/key", "report.pdf", "application/pdf");

        URI url = URI.create(download.url().toString());
        assertThat(url.getScheme()).isEqualTo("http");
        assertThat(url.getHost()).isEqualTo("storage.example");
        assertThat(url.getPort()).isEqualTo(9000);
        assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/task-attachment/key");

        Map<String, String> query = query(url);
        assertThat(query).containsEntry("X-Amz-Expires", String.valueOf(TTL.toSeconds()));
        assertThat(query.get("X-Amz-Credential")).startsWith("test-access-key/").contains("/eu-west-3/s3/");
        assertThat(query).containsKey("X-Amz-Signature");
        assertThat(query.get("X-Amz-SignedHeaders")).isEqualTo("host");
        assertThat(download.expiresAt()).isBetween(before.plus(TTL).minusSeconds(1), Instant.now().plus(TTL));
    }

    @Test
    void presignedDownloadForcesTheTypeAndAnAttachmentUnderTheOriginalName() {
        MediaDownload download = storage.presignDownload("key", "report.pdf", "application/pdf");

        Map<String, String> query = query(URI.create(download.url().toString()));
        assertThat(query).containsEntry("response-content-type", "application/pdf");

        ContentDisposition disposition = ContentDisposition.parse(query.get("response-content-disposition"));
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("report.pdf");
    }

    @Test
    void presignedDownloadEncodesNonAsciiNamesAsRfc6266ExtendedFilename() {
        MediaDownload download = storage.presignDownload("key", "résumé été.pdf", "application/pdf");

        String header = query(URI.create(download.url().toString())).get("response-content-disposition");

        assertThat(header).startsWith("attachment;").contains("filename*=UTF-8''r%C3%A9sum%C3%A9%20%C3%A9t%C3%A9.pdf");
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo("résumé été.pdf");
    }

    @Test
    void presignedDownloadCannotBeTurnedInlineByTheFilename() {
        String hostile = "x.html\"; filename=\"y.html\r\nContent-Disposition: inline";

        MediaDownload download = storage.presignDownload("key", hostile, "text/plain");

        String header = query(URI.create(download.url().toString())).get("response-content-disposition");
        assertThat(header).startsWith("attachment;").doesNotContain("\r", "\n", "inline;");
        ContentDisposition disposition = ContentDisposition.parse(header);
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo(hostile);
    }

    @Test
    void listingReturnsTheKeysModifiedBeforeTheCutoffAcrossAllPages() {
        Instant cutoff = Instant.parse("2026-09-25T03:30:00Z");
        paginateOver(
                page("page-2",
                        object("avatar/old", cutoff.minusSeconds(3600)),
                        object("avatar/recent", cutoff.plusSeconds(1))),
                page("page-3",
                        object("task-attachment/old", cutoff.minus(Duration.ofDays(40)))),
                page(null,
                        object("task-attachment/older", cutoff.minusMillis(1)))
        );

        List<String> keys = storage.listKeysModifiedBefore(cutoff);

        assertThat(keys).containsExactly("avatar/old", "task-attachment/old", "task-attachment/older");
        verify(s3Client, times(3)).listObjectsV2(listRequests.capture());
        assertThat(listRequests.getAllValues())
                .extracting(ListObjectsV2Request::bucket)
                .containsOnly(BUCKET);
        assertThat(listRequests.getAllValues())
                .extracting(ListObjectsV2Request::continuationToken)
                .containsExactly(null, "page-2", "page-3");
    }

    @Test
    void listingExcludesAnObjectModifiedExactlyAtTheCutoff() {
        Instant cutoff = Instant.parse("2026-09-25T03:30:00Z");
        paginateOver(page(null,
                object("avatar/at-cutoff", cutoff),
                object("avatar/just-before", cutoff.minusNanos(1))));

        assertThat(storage.listKeysModifiedBefore(cutoff)).containsExactly("avatar/just-before");
    }

    @Test
    void listingAnEmptyBucketReturnsNoKeys() {
        paginateOver(ListObjectsV2Response.builder().build());

        assertThat(storage.listKeysModifiedBefore(Instant.now())).isEmpty();
    }

    @Test
    void failingListingIsReportedAsStorageUnavailable() {
        SdkClientException failure = SdkClientException.create("Unable to connect");
        paginateOver();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.listKeysModifiedBefore(Instant.now()))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCause(failure);
    }

    @Test
    void failureOnALaterPageIsReportedAsStorageUnavailable() {
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(503).message("Slow down").build();
        paginateOver();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page("page-2", object("avatar/old", Instant.EPOCH)))
                .thenThrow(failure);

        assertThatThrownBy(() -> storage.listKeysModifiedBefore(Instant.now()))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCause(failure);
    }

    @SuppressWarnings("unchecked")
    private void paginateOver(ListObjectsV2Response... pages) {
        when(s3Client.listObjectsV2Paginator(any(Consumer.class))).thenAnswer(invocation -> {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder();
            invocation.<Consumer<ListObjectsV2Request.Builder>>getArgument(0).accept(builder);
            return new ListObjectsV2Iterable(s3Client, builder.build());
        });
        if (pages.length > 0) {
            List<ListObjectsV2Response> remaining = new ArrayList<>(List.of(pages));
            when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenAnswer(invocation -> remaining.removeFirst());
        }
    }

    private static ListObjectsV2Response page(String nextContinuationToken, S3Object... objects) {
        return ListObjectsV2Response
                .builder()
                .contents(objects)
                .isTruncated(nextContinuationToken != null)
                .nextContinuationToken(nextContinuationToken)
                .build();
    }

    private static S3Object object(String key, Instant lastModified) {
        return S3Object.builder().key(key).lastModified(lastModified).build();
    }

    private static Map<String, String> query(URI url) {
        return Arrays
                .stream(url.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length > 1 ? decode(pair[1]) : ""
                ));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
