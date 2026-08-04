package com.example.backend.error;

/**
 * FE가 HTTP 오류 종류를 화면 상태로 분기할 때 사용하는 공통 오류 body이다.
 * {@code traceId}는 사용자 표시값이 아니라 서버 로그와 요청을 대조하는 값이다.
 */
public record ApiErrorResponse(
		String code,
		String message,
		String traceId
) {
}
