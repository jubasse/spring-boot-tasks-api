package io.julienmetral.tasks.media.model;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Content to store, readable several times (type detection, scan, upload). */
public interface MediaSource {

    /** As sent by the client, possibly null or with path segments; sanitized by the media service. */
    String filename();

    long size();

    InputStream open() throws IOException;

    static MediaSource of(MultipartFile file) {
        return new MediaSource() {
            @Override
            public String filename() {
                return file.getOriginalFilename();
            }

            @Override
            public long size() {
                return file.getSize();
            }

            @Override
            public InputStream open() throws IOException {
                return file.getInputStream();
            }
        };
    }

    static MediaSource of(byte[] content, String filename) {
        return new MediaSource() {
            @Override
            public String filename() {
                return filename;
            }

            @Override
            public long size() {
                return content.length;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(content);
            }
        };
    }
}
