package com.project.habit_tracker.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return error(400, "Bad Request", ex.getMessage(), req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException ex, HttpServletRequest req) {
        return error(409, "Conflict", ex.getMessage(), req);
    }

    /** @Valid on @RequestBody — field-level validation failures. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(400, "Bad Request", message, req);
    }

    /** @Validated on path/query params — e.g. @Pattern on dateKey. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> {
                    String path = cv.getPropertyPath().toString();
                    String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return param + ": " + cv.getMessage();
                })
                .collect(Collectors.joining(", "));
        return error(400, "Bad Request", message, req);
    }

    /** ResponseStatusException — used by rate limiter (429) and other explicit throws. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        int status = ex.getStatusCode().value();
        return error(status, HttpStatus.resolve(status) != null
                ? HttpStatus.resolve(status).getReasonPhrase() : "Error",
                ex.getReason(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "Something went wrong", req);
    }

    private ResponseEntity<ApiError> error(int status, String error, String message, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status, error, message, req.getRequestURI()));
    }
}
