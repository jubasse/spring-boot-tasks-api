package io.julienmetral.tasks.media.services;

import io.julienmetral.tasks.media.exceptions.AntivirusUnavailableException;

import java.io.InputStream;
import java.util.Optional;

public interface VirusScanner {

    /**
     * @return the name of the threat found, or empty when the content is clean
     * @throws AntivirusUnavailableException when the scan could not run; callers must reject the upload (fail closed)
     */
    Optional<String> findThreat(InputStream content);
}
