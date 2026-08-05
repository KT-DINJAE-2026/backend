package com.example.backend.error;

import org.springframework.http.HttpStatus;

/**
 * HTTP 상태와 안정적인 FE 분기 코드를 한곳에서 관리한다.
 * enum 이름은 API의 {@code code}로 그대로 노출되므로 변경 시 FE 계약도 함께 갱신해야 한다.
 */
public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API 경로를 찾을 수 없습니다."),
	STOP_NOT_FOUND(HttpStatus.NOT_FOUND, "정류장 정보를 찾을 수 없습니다."),
	NO_DIRECT_ROUTE(HttpStatus.NOT_FOUND, "두 정류장을 잇는 직통 노선이 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
	STOP_DIRECTION_MISMATCH(HttpStatus.CONFLICT, "선택한 방향으로 이동할 수 없습니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
	UPSTREAM_FAILURE(HttpStatus.BAD_GATEWAY, "외부 서비스 응답을 처리할 수 없습니다."),
	UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 일시적으로 사용할 수 없습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 처리 중 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}
}
