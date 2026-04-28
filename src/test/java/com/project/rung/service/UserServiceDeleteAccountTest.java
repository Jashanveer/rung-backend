package com.project.rung.service;

import com.project.rung.entity.User;
import com.project.rung.entity.UserProfile;
import com.project.rung.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceDeleteAccountTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepo;
    @Autowired private UserProfileRepository profileRepo;

    /// Reproduces the user-reported bug: delete account → re-register with the
    /// same email should succeed, not bounce as "already registered". A user
    /// row must be fully gone after deleteAccount returns.
    @Test
    @Transactional
    void deleteAccountClearsUserSoEmailIsFreeAgain() {
        String email = "deletetest+" + System.nanoTime() + "@example.com";
        User user = User.builder()
                .username("deletetest" + System.nanoTime())
                .email(email)
                .passwordHash("$2a$10$dummyhashvalueforfakepassword.....")
                .build();
        userRepo.saveAndFlush(user);

        UserProfile profile = UserProfile.builder()
                .user(user)
                .displayName("Delete Test")
                .timezone(ZoneId.systemDefault().getId())
                .language(Locale.getDefault().getLanguage().toUpperCase(Locale.ROOT))
                .goals("")
                .build();
        profileRepo.saveAndFlush(profile);

        Long userId = user.getId();
        assertThat(userRepo.existsByEmail(email)).isTrue();

        userService.deleteAccount(userId);

        // A subsequent registration must see this email as free.
        assertThat(userRepo.findById(userId)).isEmpty();
        assertThat(userRepo.existsByEmail(email))
                .as("email should be free after deletion — re-register must succeed")
                .isFalse();
    }
}
