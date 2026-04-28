package com.project.habit_tracker.api;

import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerTest {

    @Test
    void deleteAccountRoutesToServiceWithAuthenticatedUserId() {
        TestUserService userService = new TestUserService();
        UserController controller = new UserController(userService);
        var principal = new JwtAuthFilter.AuthPrincipal(11L, "user@example.com", "user", true);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());

        var response = controller.deleteAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of());
        assertThat(userService.deletedUserId).isEqualTo(11L);
    }

    private static final class TestUserService extends UserService {
        private Long deletedUserId;

        private TestUserService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void deleteAccount(Long userId) {
            this.deletedUserId = userId;
        }
    }
}
