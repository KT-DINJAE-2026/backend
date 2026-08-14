package com.example.backend.holiday;

/** 한국천문연구원 특일 정보 API 실패를 원인별로 구분한다. */
public class HolidayApiException extends RuntimeException {

	private final Reason reason;

	public HolidayApiException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		/** API 비활성화, 주소 또는 인증키 누락. */
		CONFIGURATION,
		/** 인증키 미등록·만료 또는 해당 API 활용 권한 없음. */
		AUTHENTICATION,
		/** 공공데이터포털의 일일·초당 호출 한도 초과. */
		RATE_LIMITED,
		/** 비정상 HTTP 상태나 통신 실패. */
		UPSTREAM_FAILURE,
		/** 필수 XML 필드를 해석할 수 없는 응답. */
		MALFORMED_RESPONSE
	}
}
