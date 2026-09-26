package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.StorageUnavailableException;
import io.julienmetral.tasks.media.model.MediaDownload;

import java.io.InputStream;

/**
 * Where media bytes live. The implementation is chosen by {@code storage.driver} (see
 * {@code config.StorageConfiguration}); a new driver only needs a new implementation.
 * Failures surface as {@link StorageUnavailableException}.
 */
public interface ObjectStorage {

    void put(String key, InputStream content, long length, String contentType);

    void delete(String key);

    /** A time-limited URL that downloads the object under {@code filename}, without going through the application. */
    MediaDownload presignDownload(String key, String filename, String contentType);
}
