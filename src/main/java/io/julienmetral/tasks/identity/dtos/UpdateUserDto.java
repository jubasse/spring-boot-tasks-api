package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record UpdateUserDto(@Size(max = 255) @Nullable String displayName) {}
