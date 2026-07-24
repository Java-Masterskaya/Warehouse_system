package com.warehouse.controller.advice;

import com.warehouse.dto.response.error.ErrorResponse;
import com.warehouse.dto.response.error.FieldError;
import com.warehouse.dto.response.error.ValidationErrorResponse;
import com.warehouse.exception.DuplicateBarcodeException;
import com.warehouse.exception.DuplicateSkuException;
import com.warehouse.exception.DuplicateUsernameException;
import com.warehouse.exception.DuplicateWarehouseNameException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.InvalidMovementRequestException;
import com.warehouse.exception.InvalidPurchaseOrderStatusException;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.LastAdminDeactivationException;
import com.warehouse.exception.PurchaseOrderOverReceiptException;
import com.warehouse.exception.ReservationException;
import com.warehouse.exception.SelfDeactivationException;
import com.warehouse.exception.StockMovementInvariantException;
import com.warehouse.exception.StocktakeConflictException;
import com.warehouse.exception.TokenReuseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        String message;
        if (isAdmin()) {
            message = ex.getMessage();
        } else {
            message = "Resource not found";
        }
        return new ErrorResponse("ENTITY_NOT_FOUND", message);
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleInsufficientStock(InsufficientStockException ex) {
        return new ErrorResponse("INSUFFICIENT_STOCK", ex.getMessage());
    }

    @ExceptionHandler(PurchaseOrderOverReceiptException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handlePurchaseOrderOverReceipt(
            PurchaseOrderOverReceiptException ex) {
        return new ErrorResponse("PURCHASE_ORDER_OVER_RECEIPT", ex.getMessage());
    }

    @ExceptionHandler(DuplicateBarcodeException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateBarcode(DuplicateBarcodeException ex) {
        String message;
        if (isAdmin()) {
            message = ex.getMessage();
        } else {
            message = "Barcode already exists";
        }
        return new ErrorResponse("DUPLICATE_BARCODE", message);
    }

    @ExceptionHandler(DuplicateSkuException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateSku(DuplicateSkuException ex) {
        String message;
        if (isAdmin()) {
            message = ex.getMessage();
        } else {
            message = "SKU already exists";
        }
        return new ErrorResponse("DUPLICATE_SKU", message);
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateUsername(DuplicateUsernameException ex) {
        String message;
        if (isAdmin()) {
            message = ex.getMessage();
        } else {
            message = "Username already exists";
        }
        return new ErrorResponse("DUPLICATE_USERNAME", message);
    }

    @ExceptionHandler(DuplicateWarehouseNameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateWarehouseName(DuplicateWarehouseNameException ex) {
        return new ErrorResponse("DUPLICATE_WAREHOUSE_NAME", ex.getMessage());
    }

    @ExceptionHandler(LastAdminDeactivationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleLastAdminDeactivation(LastAdminDeactivationException ex) {
        return new ErrorResponse("LAST_ADMIN", ex.getMessage());
    }

    @ExceptionHandler(InvalidPurchaseOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInvalidPurchaseOrderStatus(
            InvalidPurchaseOrderStatusException ex) {
        return new ErrorResponse("INVALID_PURCHASE_ORDER_STATUS", ex.getMessage());
    }

    @ExceptionHandler(StocktakeConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleStocktakeConflict(StocktakeConflictException ex) {
        return new ErrorResponse("INVENTORY_RESULT_LESS_THAN_RESERVED", ex.getMessage());
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
        return new ErrorResponse("SELF_DEACTIVATION", ex.getMessage());
    }

    @ExceptionHandler(ReservationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnexpectedReservationStatus(ReservationException ex) {
        return new ErrorResponse("UNEXPECTED_RESERVATION_STATUS", ex.getMessage());
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

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidTokenException(InvalidTokenException ex) {
        log.warn("Invalid token: {}", ex.getMessage());
        return new ErrorResponse("INVALID_TOKEN", ex.getMessage());
    }

    @ExceptionHandler(TokenReuseException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleTokenReuseException(TokenReuseException ex) {
        log.warn("Token reuse detected: {}", ex.getMessage());
        return new ErrorResponse("TOKEN_REUSE", ex.getMessage());
    }

    @ExceptionHandler(InvalidMovementRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidMovementRequest(InvalidMovementRequestException ex) {
        log.warn("Invalid movement request: {}", ex.getMessage());
        return new ErrorResponse("INVALID_MOVEMENT_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(StockMovementInvariantException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleStockMovementInvariant(StockMovementInvariantException ex) {
        log.error("Stock movement invariant violated: {}", ex.getMessage(), ex);
        return new ErrorResponse("INTERNAL_ERROR", "Internal server error");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Concurrent stock modification detected: {}", ex.getMessage());
        return new ErrorResponse("CONCURRENT_MODIFICATION",
                "Resource was modified by another transaction. Please retry.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        String message;
        if (isAdmin()) {
            message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } else {
            message = "Internal server error";
        }
        return new ErrorResponse("INTERNAL_ERROR", message);
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        }
        return false;
    }
}
