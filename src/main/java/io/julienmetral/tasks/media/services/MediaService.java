package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.config.MediaProperties;
import io.julienmetral.tasks.identity.repositories.UserSummaryRepository;
import io.julienmetral.tasks.media.exceptions.EmptyMediaException;
import io.julienmetral.tasks.media.exceptions.InfectedMediaException;
import io.julienmetral.tasks.media.exceptions.MediaTooLargeException;
import io.julienmetral.tasks.media.exceptions.UnsupportedMediaTypeException;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.model.MediaDownload;
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

    /**
     * Validates the file against the usage's size and type rules, uploads it and saves its metadata. Must run in the
     * caller's transaction: if it rolls back, the uploaded object is deleted again.
     *
     * @throws EmptyMediaException           when the file has no content
     * @throws MediaTooLargeException        when the file exceeds the usage's size limit
     * @throws UnsupportedMediaTypeException when the detected type is not allowed for the usage
     * @throws InfectedMediaException        when the antivirus finds a threat; nothing is stored
     */
    @Transactional
    public Media store(MultipartFile file, MediaUsage usage, UUID uploadedById) {
        if (file.isEmpty()) {
            throw new EmptyMediaException();
        }

        DataSize maxSize = maxSize(usage);

        if (file.getSize() > maxSize.toBytes()) {
            throw new MediaTooLargeException(maxSize);
        }

        String filename = originalFilename(file);
        String contentType = detectContentType(file, filename);

        if (!usage.allows(contentType)) {
            throw new UnsupportedMediaTypeException(contentType, usage);
        }

        rejectIfInfected(file);

        String storageKey = usage.storagePrefix() + "/" + UUID.randomUUID();
        String sha256 = upload(file, storageKey, contentType);

        deleteObjectIfRolledBack(storageKey);

        Media media = new Media();

        media.setStorageKey(storageKey);
        media.setUsage(usage);
        media.setOriginalFilename(filename);
        media.setContentType(contentType);
        media.setSizeBytes(file.getSize());
        media.setSha256(sha256);
        media.setUploadedBy(uploadedById == null ? null : userSummaryRepository.getReferenceById(uploadedById));
        media.setCreatedAt(Instant.now());

        return mediaRepository.save(media);
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
            case AVATAR -> properties.avatarMaxSize();
            case TASK_ATTACHMENT -> properties.attachmentMaxSize();
        };
    }

    // Path segments are dropped: some clients send "C:\\Users\\...\\report.pdf"
    private static String originalFilename(MultipartFile file) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                String.valueOf(file.getOriginalFilename()).replace('\\', '/')
        ));

        if (!StringUtils.hasText(name) || "null".equals(name)) {
            return FALLBACK_FILENAME;
        }

        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String detectContentType(MultipartFile file, String filename) {
        try (InputStream content = file.getInputStream()) {
            return contentTypeDetector.detect(content, filename);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void rejectIfInfected(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            virusScanner.findThreat(content).ifPresent(threat -> {
                throw new InfectedMediaException(threat);
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Streams the file to storage and returns its SHA-256, computed on the way. */
    private String upload(MultipartFile file, String storageKey, String contentType) {
        MessageDigest digest = sha256();

        try (InputStream content = new DigestInputStream(file.getInputStream(), digest)) {
            objectStorage.put(storageKey, content, file.getSize(), contentType);
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
