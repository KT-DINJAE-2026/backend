package com.example.backend.arrival;

public class TopisApiException extends RuntimeException {

	private final Reason reason;

	public TopisApiException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		CONFIGURATION,
		AUTHENTICATION,
		INVALID_MAPPING,
		UPSTREAM_FAILURE,
		MALFORMED_RESPONSE
	}
}
