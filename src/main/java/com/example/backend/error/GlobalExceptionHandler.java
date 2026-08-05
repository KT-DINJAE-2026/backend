package com.example.backend.error;

import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import com.example.backend.arrival.TopisApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 REST 예외를 FE 공통 오류 형식으로 변환하고 추적 ID와 함께 기록한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
		return response(exception.errorCode(), exception);
	}

	/** TOPIS 장애 원인을 외부 서비스 상태와 서버 내부 매핑 오류로 분리한다. */
	@ExceptionHandler(TopisApiException.class)
	public ResponseEntity<ApiErrorResponse> handleTopisApiException(TopisApiException exception) {
		ErrorCode errorCode = switch (exception.reason()) {
			case AUTHENTICATION, CONFIGURATION -> ErrorCode.UPSTREAM_UNAVAILABLE;
			case UPSTREAM_FAILURE, MALFORMED_RESPONSE -> ErrorCode.UPSTREAM_FAILURE;
			case INVALID_MAPPING -> ErrorCode.INTERNAL_SERVER_ERROR;
		};
		return response(errorCode, exception);
	}

	@ExceptionHandler({
			ConstraintViolationException.class,
			MethodArgumentNotValidException.class,
			MissingServletRequestParameterException.class,
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
		return response(ErrorCode.INVALID_REQUEST, exception);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
		return response(ErrorCode.RESOURCE_NOT_FOUND, exception);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
			HttpRequestMethodNotSupportedException exception
	) {
		return response(ErrorCode.METHOD_NOT_ALLOWED, exception);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
			HttpMediaTypeNotSupportedException exception
	) {
		return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE, exception);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
		return response(ErrorCode.INTERNAL_SERVER_ERROR, exception);
	}

	private ResponseEntity<ApiErrorResponse> response(ErrorCode errorCode, Exception exception) {
		// 별도 분산 추적 도구가 없는 현재 단계에서는 짧은 ID로 FE 오류와 서버 로그를 연결한다.
		String traceId = UUID.randomUUID().toString().substring(0, 8);
		if (errorCode.status().is5xxServerError()) {
			log.error("traceId={} code={}", traceId, errorCode.name(), exception);
		} else {
			log.warn("traceId={} code={} message={}", traceId, errorCode.name(), exception.getMessage());
		}
		return ResponseEntity.status(errorCode.status())
				.contentType(JSON_UTF8)
				.body(new ApiErrorResponse(
						errorCode.name(),
						errorCode.message(),
						traceId
				));
	}
}
