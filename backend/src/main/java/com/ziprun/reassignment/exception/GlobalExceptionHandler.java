package com.ziprun.reassignment.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * Every error leaves the API as an RFC 7807 ProblemDetail (application/problem+json) with the right HTTP
 * status and a stable machine-readable "code".
 *
 * Extending ResponseEntityExceptionHandler means ALL of Spring MVC's own errors (404 no endpoint, 405, 406,
 * 415, missing params, bad bodies, ...) are covered with correct statuses; we only add the "code" and our
 * domain exceptions. The final catch-all guarantees nothing escapes as a stack trace or a non-problem body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- domain errors ------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail invalidTransition(InvalidTransitionException e) {
        return problem(HttpStatus.CONFLICT, "INVALID_TRANSITION", e.getMessage());
    }

    @ExceptionHandler(NoEligibleAgentException.class)
    ProblemDetail noEligible(NoEligibleAgentException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "NO_ELIGIBLE_AGENT", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception e) {
        log.warn("Concurrent modification: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The record changed at the same time; please retry.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error.");
    }

    // ---- request-validation errors (framework-raised) -----------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
        return handleExceptionInternal(e, p, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing, not valid JSON, has an invalid value (e.g. an unknown status) "
                        + "or contains an unknown field.");
        return handleExceptionInternal(e, p, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Parameter '" + e.getPropertyName() + "' has an invalid value.");
        return handleExceptionInternal(e, p, headers, status, request);
    }

    /** Funnel for every framework-raised error (404/405/406/415/...): make sure it carries a "code". */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Spring's own errors arrive with a null body; their ProblemDetail lives on the exception itself.
        if (body == null && e instanceof ErrorResponse errorResponse) {
            body = errorResponse.getBody();
        }
        if (body instanceof ProblemDetail p && (p.getProperties() == null || !p.getProperties().containsKey("code"))) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            p.setProperty("code", resolved != null ? resolved.name() : "ERROR");
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        return p;
    }
}
