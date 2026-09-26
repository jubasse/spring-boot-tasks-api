package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.services.IdenticonGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.UUID;

// Public, like an <img> tag needs it: the image is computed from the id alone, without reading the database, so
// it tells nothing about whether an account exists
@RestController
@RequestMapping(IdenticonController.PATH)
@RequiredArgsConstructor
public class IdenticonController {

    static final String PATH = "/api/v1/identicons";

    private static final MediaType SVG = MediaType.valueOf("image/svg+xml");

    private final IdenticonGenerator generator;

    /** Absolute URL for the current request's host; a relative path when there is no request (outside HTTP). */
    public static String urlOf(UUID profileId) {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(PATH + "/{id}")
                    .buildAndExpand(profileId)
                    .toUriString();
        } catch (IllegalStateException noCurrentRequest) {
            return PATH + "/" + profileId;
        }
    }

    // The image never changes for an id: browsers and proxies may keep it a year without asking again
    @GetMapping("/{id}")
    public ResponseEntity<String> identicon(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(SVG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag(id.toString())
                .body(generator.svg(id));
    }
}
