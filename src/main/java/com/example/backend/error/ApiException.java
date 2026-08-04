package com.example.backend.error;

/** 서비스 계층이 예상 가능한 API 오류를 HTTP 표현과 분리해 전달하는 예외이다. */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}
}
