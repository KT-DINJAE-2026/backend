package com.example.backend.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	STOP_NOT_FOUND(HttpStatus.NOT_FOUND, "정류장 정보를 찾을 수 없습니다."),
	NO_DIRECT_ROUTE(HttpStatus.NOT_FOUND, "두 정류장을 잇는 직통 노선이 없습니다."),
	STOP_DIRECTION_MISMATCH(HttpStatus.CONFLICT, "선택한 방향으로 이동할 수 없습니다."),
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
