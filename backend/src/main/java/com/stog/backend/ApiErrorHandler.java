package com.stog.backend;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(BasketSaveException.class)
    ResponseEntity<Map<String, Object>> basketSaveError(BasketSaveException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", exception.code());
        detail.put("message", exception.getMessage());
        if (exception.currentPlanVersion() != null) {
            detail.put("current_plan_version", exception.currentPlanVersion());
        }
        return ResponseEntity.status(exception.status()).body(Map.of("error", detail));
    }

    @ExceptionHandler(TripProductException.class)
    ResponseEntity<Map<String, Object>> tripProductError(TripProductException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", exception.code());
        detail.put("message", exception.getMessage());
        if (exception.currentPlanVersion() != null) {
            detail.put("current_plan_version", exception.currentPlanVersion());
        }
        return ResponseEntity.status(exception.status()).body(Map.of("error", detail));
    }

    @ExceptionHandler(TripCollaborationException.class)
    ResponseEntity<Map<String, Object>> collaborationError(TripCollaborationException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", exception.code());
        detail.put("message", exception.getMessage());
        if (exception.currentPlanVersion() != null) {
            detail.put("current_plan_version", exception.currentPlanVersion());
        }
        if (exception.scheduledOutsideCount() != null) {
            detail.put("scheduled_outside_count", exception.scheduledOutsideCount());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache");
        if (exception.retryAfterSeconds() != null) {
            response.header("Retry-After", Long.toString(exception.retryAfterSeconds()));
        }
        return response.body(Map.of("error", detail));
    }

    @ExceptionHandler(TripCoverException.class)
    ResponseEntity<Map<String, Object>> coverError(TripCoverException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", exception.code());
        detail.put("message", exception.getMessage());
        if (exception.currentPlanVersion() != null) {
            detail.put("current_plan_version", exception.currentPlanVersion());
        }
        return ResponseEntity.status(exception.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(Map.of("error", detail));
    }

    @ExceptionHandler(PlaceCatalogException.class)
    ResponseEntity<Map<String, Object>> placeCatalogError(PlaceCatalogException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> multipartTooLarge(MaxUploadSizeExceededException exception) {
        return error(413, "COVER_TOO_LARGE", "The cover image is too large");
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, Object>> authError(AuthException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        HttpMessageConversionException.class, MissingServletRequestPartException.class})
    ResponseEntity<Map<String, Object>> invalidRequest(Exception exception) {
        return error(400, "INVALID_REQUEST", "The request is invalid");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> databaseFailure(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return error(503, "DATABASE_BUSY", "The request could not be completed");
            }
        }
        return error(503, "DATABASE_OPERATION_FAILED", "The request could not be completed");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
