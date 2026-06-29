package com.warehouse.controller.advice;

import com.warehouse.dto.response.error.ErrorResponse;
import com.warehouse.dto.response.error.FieldError;
import com.warehouse.dto.response.error.ValidationErrorResponse;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.DuplicateUsernameException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.SelfDeactivationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * GlobalExceptionHandler обрабатывает исключения, которые выбрасываются из контроллеров:
 * сущностные ошибки (Not found, Insufficient stock, Duplicate), валидацию и общие ошибки.
 *
 * <p>Обработка исключений 401 (Unauthorized) и 403 (Forbidden) происходит в
 * {@link com.warehouse.security.config.SecurityConfig SecurityConfig} через
 * {@link com.warehouse.security.config.SecurityConfig#authenticationEntryPoint() authenticationEntryPoint}
 * и {@link com.warehouse.security.config.SecurityConfig#accessDeniedHandler() accessDeniedHandler},
 * которые возвращают JSON-ответ.
 * Эти обработчики в SecurityConfig срабатывают до контроллера, так как:</p>
 *
 * <ul>
 *   <li>Проверка токена происходит на уровне фильтров (JwtAuthFilter)</li>
 *   <li>Проверка ролей происходит на уровне @PreAuthorize аннотаций</li>
 * </ul>
 *
 * <p>Обработчики 401/403 в этом классе остаются только для совместимости с тестами,
 * которые ожидают JSON-ответ через Spring MVC контроллеры.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleEntityNotFound(EntityNotFoundException ex) {
        return new ErrorResponse("ENTITY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleInsufficientStock(InsufficientStockException ex) {
        return new ErrorResponse("INSUFFICIENT_STOCK", ex.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateSku(DuplicateSkuException ex) {
        return new ErrorResponse("DUPLICATE_SKU", ex.getMessage());
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateUsername(DuplicateUsernameException ex) {
        return new ErrorResponse("DUPLICATE_USERNAME", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ValidationErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return new ValidationErrorResponse("VALIDATION_ERROR", fieldErrors);
    }

    @ExceptionHandler(SelfDeactivationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleSelfDeactivation(SelfDeactivationException ex) {
        return new  ErrorResponse("SELF_DEACTIVATION", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDenied(AccessDeniedException ex) {
        return new ErrorResponse("ACCESS_DENIED", "Access denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleAuthentication(AuthenticationException ex) {
        return new ErrorResponse("UNAUTHORIZED", "Authentication failed");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return new ErrorResponse("INTERNAL_ERROR", "Internal server error");
    }
}