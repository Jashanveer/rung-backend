package com.project.habit_tracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends transactional and scheduled emails.
 *
 * Gracefully skips if MAIL_HOST is not configured — just logs a warning.
 * Uses plain-text bodies (no template engine dependency).
 *
 * Set these environment variables to enable email:
 *   MAIL_HOST        SMTP server host (e.g. smtp.gmail.com)
 *   MAIL_PORT        SMTP port (default 587)
 *   MAIL_USERNAME    SMTP username / email address
 *   MAIL_PASSWORD    SMTP password or app-specific password
 *   MAIL_FROM        From address (default noreply@habit-tracker.app)
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.email.from:noreply@habit-tracker.app}")
    private String fromAddress;

    @Value("${app.reset-base-url:https://habit-tracker.app/reset}")
    private String resetBaseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Sent immediately after a new user registers. */
    public void sendWelcome(String toEmail, String displayName) {
        if (!emailEnabled()) return;
        String subject = "Welcome to Habit Tracker, " + displayName + "!";
        String body = """
                Hi %s,

                Welcome aboard! You've just taken the first step toward building lasting habits.

                Here's how to get started:
                  • Add your first habit on the main screen
                  • Check it off each day to build a streak
                  • After 7 days of data, you unlock mentor matching

                Your consistency score improves every week you show up. Small steps, done daily, compound into remarkable results.

                See you in there,
                The Habit Tracker Team
                """.formatted(displayName);
        send(toEmail, subject, body);
    }

    /** Sent before account creation to verify email ownership. */
    public void sendEmailVerification(String toEmail, String code, long ttlMinutes) {
        if (!emailEnabled()) return;
        String subject = "Verify your Habit Tracker email";
        String body = """
                Hi,

                Use this code to finish creating your Habit Tracker account:

                  %s

                This code expires in %d minutes.

                If you didn't request this, you can safely ignore this email.

                The Habit Tracker Team
                """.formatted(code, ttlMinutes);
        send(toEmail, subject, body);
    }

    /** Sent when a user requests a password reset. */
    public void sendPasswordReset(String toEmail, String displayName, String token) {
        if (!emailEnabled()) return;
        String resetLink = resetBaseUrl + "?token=" + token;
        String subject = "Reset your Habit Tracker password";
        String body = """
                Hi %s,

                We received a request to reset the password for your Habit Tracker account.

                Click the link below to set a new password (valid for 24 hours):

                  %s

                If you didn't request this, you can safely ignore this email — your password won't change.

                Stay consistent,
                The Habit Tracker Team
                """.formatted(displayName, resetLink);
        send(toEmail, subject, body);
    }

    /** Weekly reflection email — sent every Sunday. Personalised from stats. */
    public void sendWeeklyReflection(String toEmail, String displayName, WeeklyReflectionData data) {
        if (!emailEnabled()) return;
        String subject = "Your habit week in review — " + data.level();
        String body = buildReflectionBody(displayName, data);
        send(toEmail, subject, body);
    }

    // ── Body builder ────────────────────────────────────────────────────────

    private String buildReflectionBody(String displayName, WeeklyReflectionData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(displayName).append(",\n\n");

        // Opening — personalised to consistency level
        if (data.weeklyConsistencyPercent() >= 90) {
            sb.append("What a week. You hit ").append(data.weeklyConsistencyPercent())
              .append("% consistency — that puts you in the top tier of habit builders. "
                    + "The compounding effect of this kind of consistency is real.\n\n");
        } else if (data.weeklyConsistencyPercent() >= 60) {
            sb.append("Solid week. You finished ").append(data.weeklyConsistencyPercent())
              .append("% of your habits — that's genuine progress. "
                    + "The gap between 60% and 90% is mostly just showing up on the hard days.\n\n");
        } else if (data.weeklyConsistencyPercent() > 0) {
            sb.append("This week was a mixed bag at ").append(data.weeklyConsistencyPercent())
              .append("% consistency, and that's okay. Every week is a fresh start. "
                    + "The fact you're still here means you haven't given up.\n\n");
        } else {
            sb.append("This week was quiet on the habit front. "
                    + "Sometimes life gets in the way — the important thing is coming back.\n\n");
        }

        // Perfect days
        if (data.perfectDaysThisWeek() >= 5) {
            sb.append("You had ").append(data.perfectDaysThisWeek())
              .append(" perfect days this week. That's extraordinary. "
                    + "You're building the kind of discipline that changes lives.\n\n");
        } else if (data.perfectDaysThisWeek() >= 1) {
            sb.append("You had ").append(data.perfectDaysThisWeek())
              .append(" perfect day").append(data.perfectDaysThisWeek() > 1 ? "s" : "")
              .append(" this week. Each one is worth celebrating.\n\n");
        }

        // Streak highlight
        if (data.bestStreak() >= 21) {
            sb.append("Your best streak is ").append(data.bestStreak())
              .append(" days — that's a deeply embedded habit. Protect it.\n\n");
        } else if (data.bestStreak() >= 7) {
            sb.append("Your best streak stands at ").append(data.bestStreak())
              .append(" days. Keep going — the 21-day threshold is where habits start to feel automatic.\n\n");
        }

        // Badges
        if (!data.badges().isEmpty()) {
            sb.append("Badges earned: ").append(String.join(", ", data.badges())).append(".\n\n");
        }

        // Mentor tip
        if (data.mentorTip() != null && !data.mentorTip().isBlank()) {
            sb.append("This week's focus: ").append(data.mentorTip()).append("\n\n");
        }

        // Closing
        sb.append("See you next week,\n");
        sb.append("The Habit Tracker Team\n");

        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean emailEnabled() {
        if (mailHost == null || mailHost.isBlank()) {
            log.debug("Email sending skipped — MAIL_HOST not configured.");
            return false;
        }
        return true;
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (Exception e) {
            // Never let an email failure crash the caller
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    // ── Data carrier ─────────────────────────────────────────────────────────

    public record WeeklyReflectionData(
            int weeklyConsistencyPercent,
            int totalHabits,
            int bestStreak,
            int perfectDaysThisWeek,
            String level,
            List<String> badges,
            String mentorTip
    ) {}
}
