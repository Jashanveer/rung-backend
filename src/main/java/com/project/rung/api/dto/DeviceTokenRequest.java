package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceTokenRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Pattern(regexp = "^(macos|ios)$", message = "platform must be macos or ios") String platform
) {}
