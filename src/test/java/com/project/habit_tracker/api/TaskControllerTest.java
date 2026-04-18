package com.project.habit_tracker.api;

import com.project.habit_tracker.security.JwtAuthFilter;
import com.project.habit_tracker.service.HabitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskControllerTest {

    @Test
    void deleteReturnsJsonObjectForClientsThatDecodeEmptyResponses() {
        TestHabitService habitService = new TestHabitService();
        TaskController controller = new TaskController(habitService);
        var principal = new JwtAuthFilter.AuthPrincipal(7L, "user@example.com", "user");
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());

        var response = controller.delete(authentication, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of());
        assertThat(habitService.deletedUserId).isEqualTo(7L);
        assertThat(habitService.deletedTaskId).isEqualTo(42L);
    }

    private static final class TestHabitService extends HabitService {
        private Long deletedUserId;
        private Long deletedTaskId;

        private TestHabitService() {
            super(null, null, null, null, null);
        }

        @Override
        public void deleteTask(Long userId, Long taskId) {
            this.deletedUserId = userId;
            this.deletedTaskId = taskId;
        }
    }
}
