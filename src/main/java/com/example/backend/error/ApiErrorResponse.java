package com.example.backend.error;

public record ApiErrorResponse(
		String code,
		String message,
		String traceId
) {
}
