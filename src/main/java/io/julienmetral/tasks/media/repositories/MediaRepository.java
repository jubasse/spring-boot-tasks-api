package io.julienmetral.tasks.media.repositories;

import io.julienmetral.tasks.media.model.Media;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaRepository extends JpaRepository<Media, UUID> {}
