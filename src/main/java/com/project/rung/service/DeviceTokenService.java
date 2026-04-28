package com.project.rung.service;

import com.project.rung.entity.DeviceToken;
import com.project.rung.entity.User;
import com.project.rung.repository.DeviceTokenRepository;
import com.project.rung.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DeviceTokenService {

    private final UserRepository userRepo;
    private final DeviceTokenRepository tokenRepo;

    public DeviceTokenService(UserRepository userRepo, DeviceTokenRepository tokenRepo) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
    }

    @Transactional
    public void registerToken(Long userId, String token, String platform) {
        User user = userRepo.findById(userId).orElseThrow();
        Instant now = Instant.now();
        tokenRepo.findByToken(token).ifPresentOrElse(
                existing -> {
                    existing.setUser(user);
                    existing.setPlatform(platform);
                    existing.setUpdatedAt(now);
                    tokenRepo.save(existing);
                },
                () -> tokenRepo.save(DeviceToken.builder()
                        .user(user)
                        .token(token)
                        .platform(platform)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
        );
    }

    public List<DeviceToken> tokensForUser(User user) {
        return tokenRepo.findAllByUser(user);
    }
}
