package com.example.backend.arrival;

/**
 * TOPIS 호출 실패를 원인별로 구분하는 예외이다.
 *
 * <p>사용자 문구가 아니라 재시도 여부, 설정 수정 여부와 운영 로그 분류에 사용한다.</p>
 */
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
		/** 인증키 누락이나 형식 오류. */
		CONFIGURATION,
		/** 인증키 미등록 또는 API 활용 승인 실패. */
		AUTHENTICATION,
		/** 정류장·노선·순번 매핑 오류. */
		INVALID_MAPPING,
		/** TOPIS 비정상 HTTP 응답이나 통신 실패. */
		UPSTREAM_FAILURE,
		/** 정상 응답으로 처리할 수 없는 XML 형식. */
		MALFORMED_RESPONSE
	}
}
