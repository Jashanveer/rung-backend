package com.project.habit_tracker.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional and scheduled emails.
 *
 * Verification and welcome emails use HTML templates from
 * src/main/resources/templates/. Password reset and weekly reflection
 * use plain text.
 *
 * Gracefully skips all sending if MAIL_HOST is not configured.
 *
 * Environment variables:
 *   MAIL_HOST        SMTP server host (e.g. smtp.gmail.com)
 *   MAIL_PORT        SMTP port (default 587)
 *   MAIL_USERNAME    SMTP username / email address
 *   MAIL_PASSWORD    SMTP password or app-specific password
 *   MAIL_FROM        From address (default noreply@habit-tracker.app)
 *   APP_URL          Deep-link or App Store URL shown in the welcome email
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

    @Value("${app.email.app-url:https://habit-tracker.app}")
    private String appUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Sent immediately after a new user registers. */
    public void sendWelcome(String toEmail, String displayName) {
        if (!emailEnabled()) return;
        String html = renderTemplate("templates/welcome.html", Map.of(
                "{{FIRST_NAME}}", displayName,
                "{{APP_URL}}",    appUrl,
                "{{USER_EMAIL}}", toEmail
        ));
        sendHtml(toEmail, "Welcome to Habit Tracker, " + displayName + "!", html);
    }

    /** Sent before account creation to verify email ownership. */
    public void sendEmailVerification(String toEmail, String code, long ttlMinutes) {
        if (!emailEnabled()) {
            log.warn("Email not configured — verification code for {} : {}", toEmail, code);
            return;
        }
        String html = renderTemplate("templates/email-verification.html", Map.of(
                "{{OTP_CODE}}",            code,
                "{{OTP_EXPIRY_MINUTES}}", String.valueOf(ttlMinutes),
                "{{USER_EMAIL}}",          toEmail
        ));
        sendHtml(toEmail, "Verify your Habit Tracker email", html);
    }

    /** Sent when a user requests a password reset. */
    public void sendPasswordReset(String toEmail, String displayName, String token) {
        if (!emailEnabled()) return;
        String resetLink = resetBaseUrl + "?token=" + token;
        String body = """
                Hi %s,

                We received a request to reset the password for your Habit Tracker account.

                Click the link below to set a new password (valid for 24 hours):

                  %s

                If you didn't request this, you can safely ignore this email — your password won't change.

                Stay consistent,
                The Habit Tracker Team
                """.formatted(displayName, resetLink);
        sendPlain(toEmail, "Reset your Habit Tracker password", body);
    }

    /** Weekly reflection email — sent every Sunday. Personalised from stats. */
    public void sendWeeklyReflection(String toEmail, String displayName, WeeklyReflectionData data) {
        if (!emailEnabled()) return;
        sendPlain(toEmail, "Your habit week in review — " + data.level(),
                buildReflectionBody(displayName, data));
    }

    // ── Template rendering ───────────────────────────────────────────────────

    private String renderTemplate(String path, Map<String, String> vars) {
        String html = loadTemplate(path);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            html = html.replace(entry.getKey(), entry.getValue());
        }
        return html;
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load email template: " + path, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean emailEnabled() {
        if (mailHost == null || mailHost.isBlank()) {
            log.debug("Email sending skipped — MAIL_HOST not configured.");
            return false;
        }
        return true;
    }

    // ── Senders ──────────────────────────────────────────────────────────────

    private void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    private void sendPlain(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (RuntimeException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    // ── Plain-text body builders ─────────────────────────────────────────────

    private String buildReflectionBody(String displayName, WeeklyReflectionData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(displayName).append(",\n\n");

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

        if (data.perfectDaysThisWeek() >= 5) {
            sb.append("You had ").append(data.perfectDaysThisWeek())
              .append(" perfect days this week. That's extraordinary. "
                    + "You're building the kind of discipline that changes lives.\n\n");
        } else if (data.perfectDaysThisWeek() >= 1) {
            sb.append("You had ").append(data.perfectDaysThisWeek())
              .append(" perfect day").append(data.perfectDaysThisWeek() > 1 ? "s" : "")
              .append(" this week. Each one is worth celebrating.\n\n");
        }

        if (data.bestStreak() >= 21) {
            sb.append("Your best streak is ").append(data.bestStreak())
              .append(" days — that's a deeply embedded habit. Protect it.\n\n");
        } else if (data.bestStreak() >= 7) {
            sb.append("Your best streak stands at ").append(data.bestStreak())
              .append(" days. Keep going — the 21-day threshold is where habits start to feel automatic.\n\n");
        }

        if (!data.badges().isEmpty()) {
            sb.append("Badges earned: ").append(String.join(", ", data.badges())).append(".\n\n");
        }

        if (data.mentorTip() != null && !data.mentorTip().isBlank()) {
            sb.append("This week's focus: ").append(data.mentorTip()).append("\n\n");
        }

        sb.append("See you next week,\n");
        sb.append("The Habit Tracker Team\n");
        return sb.toString();
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
