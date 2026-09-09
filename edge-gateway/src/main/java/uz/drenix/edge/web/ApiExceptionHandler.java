package uz.drenix.edge.web;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.Permissions;

/**
 * Translates internal failures into the public error shape.
 *
 * <p>Descriptions from gRPC are passed through only for statuses we raise deliberately
 * (INVALID_ARGUMENT, NOT_FOUND, PERMISSION_DENIED). Everything else collapses to a generic
 * message, because an INTERNAL description can carry a stack trace or a SQL fragment.
 *
 * <p>The client-error handlers below exist so that a malformed body, an unknown enum name or a
 * bad query parameter answers 400 rather than falling through to the catch-all and reporting a
 * server fault for what is a caller mistake.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ApiError> handleGrpc(StatusRuntimeException exception) {
        Status.Code code = exception.getStatus().getCode();
        String description = exception.getStatus().getDescription();

        return switch (code) {
            case INVALID_ARGUMENT -> ResponseEntity.badRequest()
                    .body(ApiError.of("invalid_request", safe(description, "Request is not valid.")));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiError.of("not_found", safe(description, "Not found.")));
            case ALREADY_EXISTS -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of("conflict", safe(description, "Already exists.")));
            case PERMISSION_DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiError.of("forbidden", "You do not have permission to do that."));
            case UNAUTHENTICATED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiError.of("unauthenticated", "Please sign in."));
            // The description is written by us, not by the failure, so it is safe to pass through:
            // the caller needs to know this is a busy upstream and roughly when to come back.
            case RESOURCE_EXHAUSTED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiError.of("rate_limited",
                            safe(description, "Too many requests. Please try again shortly.")));
            // Something upstream has to be fixed before this can work — a missing key, an empty
            // billing balance. The description is written by us, not by the failure, so passing
            // it on tells the caller what to do instead of leaving them to guess.
            case FAILED_PRECONDITION -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiError.of("unavailable",
                            safe(description, "Service is temporarily unavailable.")));
            case DEADLINE_EXCEEDED, UNAVAILABLE -> {
                log.error("Downstream service unavailable", exception);
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiError.of("unavailable", "Service is temporarily unavailable."));
            }
            default -> {
                log.error("Unhandled gRPC failure", exception);
                yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiError.of("internal_error", "Something went wrong."));
            }
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request is not valid.");
        return ResponseEntity.badRequest().body(ApiError.of("invalid_request", detail));
    }

    /**
     * Constraints on the handler parameters themselves — {@code @Pattern} on a query parameter,
     * {@code @Min} on a limit.
     *
     * <p>The violation message is passed through. Unlike a parser's message it was written by hand
     * on the annotation, for the caller, and it is the only thing that says which of several query
     * parameters was rejected: {@code ?month=banana} used to answer "Request is not valid." and
     * leave the caller to guess, while the annotation next to the parameter already said "month
     * must look like 2026-08".
     */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleParameterValidation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request",
                        safe(violationMessage(exception), "Request is not valid.")));
    }

    /** The first violation's own message, or null when the exception carries none. */
    private static String violationMessage(Exception exception) {
        if (exception instanceof ConstraintViolationException violations) {
            return violations.getConstraintViolations().stream()
                    .findFirst()
                    .map(jakarta.validation.ConstraintViolation::getMessage)
                    .orElse(null);
        }
        if (exception instanceof HandlerMethodValidationException handler) {
            return handler.getAllErrors().stream()
                    .findFirst()
                    .map(org.springframework.context.MessageSourceResolvable::getDefaultMessage)
                    .orElse(null);
        }
        return null;
    }

    /**
     * A body that is not JSON, or JSON that cannot become the target record. The parser message
     * quotes the input and names internal types, so it is logged rather than returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("Malformed request body", exception);
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", "Request body is not valid JSON."));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParameter(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", "A request parameter is missing or malformed."));
    }

    /**
     * Raised by our own mapping code for a value the caller chose — an unknown entity name, for
     * instance. It is a client error, not a fault.
     */
    /**
     * A rejection this service wrote on purpose. The message is meant for the caller, so it is
     * the one thing they get back.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request",
                        safe(exception.getMessage(), "Request is not valid.")));
    }

    /**
     * Anything else that reports a bad value by throwing. Its message belongs to whatever library
     * raised it and can name internal types, so it is logged rather than returned; throw
     * {@link BadRequestException} when the caller should be told why.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        log.debug("Rejected request value", exception);
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", "Request is not valid."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("method_not_allowed", "That method is not supported here."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("unsupported_media_type", "Send the body as application/json."));
    }

    /** An unmapped path. Without this it would reach the catch-all and be reported as a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "Not found."));
    }

    /**
     * Refused by a controller rather than by Spring Security.
     *
     * <p>Both mean the same thing to a caller. The platform one is thrown where the rule needs the
     * request to have been read first — asking for another company's chart is only visible as a
     * denial once the query parameter is parsed, which is well past where @PreAuthorize runs.
     */
    /**
     * Something the caller asked for by id does not exist, or is not theirs.
     *
     * <p>Thrown by a controller rather than by a downstream service, so it never passes through
     * the gRPC translation that would otherwise have mapped it. Without this it surfaced as a 500
     * for what is an ordinary "no such thing".
     */
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<ApiError> handleMissing(java.util.NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found",
                        exception.getMessage() == null ? "Not found." : exception.getMessage()));
    }

    @ExceptionHandler(uz.drenix.platform.security.AccessDeniedException.class)
    public ResponseEntity<ApiError> handlePlatformAccessDenied(
            uz.drenix.platform.security.AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "You do not have permission to do that."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        // A token issued during a forced password change carries exactly one permission, so it is
        // refused everywhere else. Saying only "you do not have permission" sends the caller off
        // to check role assignments that are perfectly correct; the account is simply not finished
        // signing in yet. Naming that is not a disclosure — the caller already knows, because the
        // login response that produced this token told them.
        if (isPasswordChangeScoped()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiError.of("password_change_required",
                            "This token can only change your password. Call "
                            + "POST /api/v1/auth/password, then sign in again to get a full token."));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "You do not have permission to do that."));
    }

    private static boolean isPasswordChangeScoped() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
               && authentication.getPrincipal() instanceof AuthPrincipal principal
               && principal.permissions().equals(Set.of(Permissions.AUTH_CHANGE_OWN_PASSWORD));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled error at the edge", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "Something went wrong."));
    }

    private static String safe(String description, String fallback) {
        return description == null || description.isBlank() ? fallback : description;
    }
}
