package io.julienmetral.tasks.media.services;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Detects a file's type from its leading bytes. The file name only refines a type the bytes already show: a ZIP
 * named {@code .docx} becomes a Word document, but an executable renamed {@code .pdf} stays an executable.
 */
@Component
public class ContentTypeDetector {

    private final Tika tika = new Tika();

    public String detect(InputStream content, String filename) throws IOException {
        return tika.detect(content, filename);
    }
}
