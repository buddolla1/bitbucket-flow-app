package com.jira.analytics.exception;

import java.io.IOException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            NoSuchElementException.class,
            MaxUploadSizeExceededException.class,
            IOException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
        log.info("Returning bad request response errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Returning internal server error response errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(exception.getMessage() == null ? "Unexpected server error" : exception.getMessage()));
    }
}
