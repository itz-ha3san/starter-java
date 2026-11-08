package edu.softwarelab.starter.api;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import edu.softwarelab.starter.service.TransferService.IdempotencyKeyReusedException;

/**
 * Maps service-layer exceptions to RFC 9457 Problem Details responses with
 * content type {@code application/problem+json}.
 *
 * <p>Each ProblemDetail carries the standard fields (type, title, status,
 * detail) plus two custom properties required by the OpenAPI contract:
 * <ul>
 *   <li>{@code code} — stable machine-readable code from the enum in
 *       {@code openapi/payment.yaml} (VALIDATION_ERROR, NOT_FOUND, CONFLICT,
 *       SERVICE_UNAVAILABLE, UPSTREAM_TIMEOUT).</li>
 *   <li>{@code correlation_id} — UUID propagated from the MDC (set by
 *       {@link CorrelationIdFilter}) so every log line and every error
 *       response share the same id.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_TYPE_BASE = "https://errors.labbank.example/problem";

    /** Content type per RFC 9457. */
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    /* ----------------------------------------------------------------- */

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key reused", ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Missing required header",
                ex.getHeaderName() + " header is required");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request body",
                "Request body is missing or is not valid JSON");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request", detail);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, WebRequest req) {
        log.error("Unhandled exception on {}", req.getDescription(false), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "SERVICE_UNAVAILABLE",
                "Internal server error",
                "An unexpected error occurred");
    }

    /* ----------------------------------------------------------------- */

    private ResponseEntity<ProblemDetail> problem(HttpStatus status,
                                                  String code,
                                                  String title,
                                                  String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(PROBLEM_TYPE_BASE + "/" + code.toLowerCase()));
        pd.setTitle(title);
        pd.setProperty("code", code);
        pd.setProperty("correlation_id", currentCorrelationId());

        log.warn("problem status={} code={} detail='{}' correlation_id={}",
                status.value(), code, detail, pd.getProperties().get("correlation_id"));

        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(pd);
    }

    private static String currentCorrelationId() {
        String fromMdc = MDC.get("correlation_id");
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return UUID.randomUUID().toString();
    }
}
