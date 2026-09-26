package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaProperties;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
import io.julienmetral.tasks.media.model.MediaSource;
import io.julienmetral.tasks.media.model.MediaUsage;
import io.julienmetral.tasks.media.repositories.MediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final String FALLBACK_FILENAME = "file";

    private final MediaRepository mediaRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final ObjectStorage objectStorage;
    private final ContentTypeDetector contentTypeDetector;
    private final VirusScanner virusScanner;
    private final MediaProperties properties;

    @Transactional
    public Media store(MultipartFile file, MediaUsage usage, UUID uploadedById) {
        return store(MediaSource.of(file), usage, uploadedById);
    }

    /**
     * Validates the content (see {@link #validate}), uploads it and saves its metadata. Must run in the caller's
     * transaction: if it rolls back, the uploaded object is deleted again.
     */
    @Transactional
    public Media store(MediaSource source, MediaUsage usage, UUID uploadedById) {
        String contentType = validate(source, usage);
        String filename = originalFilename(source.filename());

        String storageKey = usage.storagePrefix() + "/" + UUID.randomUUID();
        String sha256 = upload(source, storageKey, contentType);

        deleteObjectIfRolledBack(storageKey);

        Media media = new Media();

        media.setStorageKey(storageKey);
        media.setUsage(usage);
        media.setOriginalFilename(filename);
        media.setContentType(contentType);
        media.setSizeBytes(source.size());
        media.setSha256(sha256);
        media.setUploadedBy(uploadedById == null ? null : userSummaryRepository.getReferenceById(uploadedById));
        media.setCreatedAt(Instant.now());

        return mediaRepository.save(media);
    }

    /**
     * Checks the content against the usage's rules without storing it, for callers that transform the file first
     * (a profile photo is validated as uploaded, then re-encoded).
     *
     * @return the content type detected from the bytes
     * @throws EmptyMediaException           when the content is empty
     * @throws MediaTooLargeException        when it exceeds the usage's size limit
     * @throws UnsupportedMediaTypeException when the detected type is not allowed for the usage
     * @throws InfectedMediaException        when the antivirus finds a threat
     */
    public String validate(MediaSource source, MediaUsage usage) {
        if (source.size() == 0) {
            throw new EmptyMediaException();
        }

        DataSize maxSize = maxSize(usage);

        if (source.size() > maxSize.toBytes()) {
            throw new MediaTooLargeException(maxSize);
        }

        String contentType = detectContentType(source, originalFilename(source.filename()));

        if (!usage.allows(contentType)) {
            throw new UnsupportedMediaTypeException(contentType, usage);
        }

        rejectIfInfected(source);

        return contentType;
    }

    /** Deletes the row now and the stored object once the transaction commits (kept if it rolls back). */
    @Transactional
    public void delete(Media media) {
        mediaRepository.delete(media);

        String storageKey = media.getStorageKey();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorage.delete(storageKey);
                } catch (RuntimeException exception) {
                    log.warn("Could not delete object {} of a deleted media", storageKey, exception);
                }
            }
        });
    }

    /** Reads the whole stored file; meant for small files processed in memory, such as profile photos. */
    public byte[] read(Media media) {
        try (InputStream content = objectStorage.open(media.getStorageKey())) {
            return content.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public MediaDownload downloadUrl(Media media) {
        return objectStorage.presignDownload(
                media.getStorageKey(),
                media.getOriginalFilename(),
                media.getContentType()
        );
    }

    private DataSize maxSize(MediaUsage usage) {
        return switch (usage) {
            case AVATAR, AVATAR_UPLOAD -> properties.avatarMaxSize();
            case TASK_ATTACHMENT -> properties.attachmentMaxSize();
        };
    }

    // Path segments are dropped: some clients send "C:\\Users\\...\\report.pdf"
    private static String originalFilename(String sent) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                String.valueOf(sent).replace('\\', '/')
        ));

        if (!StringUtils.hasText(name) || "null".equals(name)) {
            return FALLBACK_FILENAME;
        }

        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String detectContentType(MediaSource source, String filename) {
        try (InputStream content = source.open()) {
            return contentTypeDetector.detect(content, filename);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void rejectIfInfected(MediaSource source) {
        try (InputStream content = source.open()) {
            virusScanner.findThreat(content).ifPresent(threat -> {
                throw new InfectedMediaException(threat);
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Streams the file to storage and returns its SHA-256, computed on the way. */
    private String upload(MediaSource source, String storageKey, String contentType) {
        MessageDigest digest = sha256();

        try (InputStream content = new DigestInputStream(source.open(), digest)) {
            objectStorage.put(storageKey, content, source.size(), contentType);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private void deleteObjectIfRolledBack(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        objectStorage.delete(storageKey);
                    } catch (RuntimeException exception) {
                        log.warn("Could not delete object {} after a rollback", storageKey, exception);
                    }
                }
            }
        });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to support SHA-256
            throw new IllegalStateException(exception);
        }
    }
}
