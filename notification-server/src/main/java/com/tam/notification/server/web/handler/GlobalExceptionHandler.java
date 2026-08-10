package com.tam.notification.server.web.handler;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.shortlink.exception.ShortLinkExpiredException;
import com.tam.notification.shortlink.exception.ShortLinkNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleShortLinkNotFound(ShortLinkNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(
                        e.getErrorCode().getCode(),
                        e.getMessage()
                ));
    }

    @ExceptionHandler(ShortLinkExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleShortLinkExpired(ShortLinkExpiredException e) {

        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(ApiResponse.failure(
                        e.getErrorCode().getCode(),
                        e.getMessage()
                ));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("business exception: code = {}, message = {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failure(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(CommonErrorCode.INVALID_PARAMETER.getCode());

        log.warn("validation exception message = {}", message);

        return ResponseEntity.badRequest().body(ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER.getCode(), message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownException(Exception exception) {
        log.error("Unexpected system exception", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR.getCode(), CommonErrorCode.INTERNAL_ERROR.getMessage()));
    }
}
