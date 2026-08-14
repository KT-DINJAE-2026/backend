package com.example.backend.weather;

/** Open-Meteo 예보 조회 실패를 원인별로 구분한다. */
public class WeatherApiException extends RuntimeException {

	private final Reason reason;

	public WeatherApiException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	public enum Reason {
		/** API 비활성화, 주소 누락, 정류장 좌표 누락·범위 오류. */
		CONFIGURATION,
		/** Open-Meteo의 호출 제한 응답. */
		RATE_LIMITED,
		/** 비정상 HTTP 상태나 통신 실패. */
		UPSTREAM_FAILURE,
		/** 시간 배열과 WMO 코드 배열이 일치하지 않는 등 잘못된 JSON. */
		MALFORMED_RESPONSE,
		/** 정상 응답이지만 차량의 승차 예정 시간에 해당하는 예보가 없음. */
		NO_FORECAST
	}
}
