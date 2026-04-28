package com.project.rung.api;

import com.project.rung.api.dto.DeviceTokenRequest;
import com.project.rung.security.JwtAuthFilter;
import com.project.rung.service.DeviceTokenService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceTokenService deviceTokenService;

    public DeviceController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping("/token")
    public ResponseEntity<Void> registerToken(
            Authentication auth,
            @Valid @RequestBody DeviceTokenRequest req
    ) {
        deviceTokenService.registerToken(userId(auth), req.token(), req.platform());
        return ResponseEntity.ok().build();
    }

    private Long userId(Authentication auth) {
        return ((JwtAuthFilter.AuthPrincipal) auth.getPrincipal()).userId();
    }
}
