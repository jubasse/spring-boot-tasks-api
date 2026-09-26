package io.julienmetral.tasks.media.repositories;

import io.julienmetral.tasks.media.model.Media;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MediaRepository extends JpaRepository<Media, UUID> {
}
