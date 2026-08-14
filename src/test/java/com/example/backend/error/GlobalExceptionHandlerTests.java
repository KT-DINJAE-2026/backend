package com.example.backend.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.example.backend.arrival.TopisApiException;
import com.example.backend.holiday.HolidayApiException;
import com.example.backend.weather.WeatherApiException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 외부 API 장애가 FE 공통 오류 계약과 적절한 HTTP 상태로 변환되는지 검증한다. */
class GlobalExceptionHandlerTests {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void mapsTopisReasonsWithoutChangingTheErrorBodyContract() {
		Map<TopisApiException.Reason, ErrorCode> expectedCodes = Map.of(
				TopisApiException.Reason.AUTHENTICATION, ErrorCode.UPSTREAM_UNAVAILABLE,
				TopisApiException.Reason.CONFIGURATION, ErrorCode.UPSTREAM_UNAVAILABLE,
				TopisApiException.Reason.UPSTREAM_FAILURE, ErrorCode.UPSTREAM_FAILURE,
				TopisApiException.Reason.MALFORMED_RESPONSE, ErrorCode.UPSTREAM_FAILURE,
				TopisApiException.Reason.INVALID_MAPPING, ErrorCode.INTERNAL_SERVER_ERROR
		);

		expectedCodes.forEach((reason, expectedCode) -> {
			ResponseEntity<ApiErrorResponse> response = handler.handleTopisApiException(
					new TopisApiException(reason, "test")
			);
			assertThat(response.getStatusCode()).isEqualTo(expectedCode.status());
			assertThat(response.getBody()).isNotNull().satisfies(body -> {
				assertThat(body.code()).isEqualTo(expectedCode.name());
				assertThat(body.message()).isEqualTo(expectedCode.message());
				assertThat(body.traceId()).hasSize(8);
			});
		});
	}

	@Test
	void mapsHolidayReasonsWithoutChangingTheErrorBodyContract() {
		Map<HolidayApiException.Reason, ErrorCode> expectedCodes = Map.of(
				HolidayApiException.Reason.AUTHENTICATION, ErrorCode.UPSTREAM_UNAVAILABLE,
				HolidayApiException.Reason.CONFIGURATION, ErrorCode.UPSTREAM_UNAVAILABLE,
				HolidayApiException.Reason.RATE_LIMITED, ErrorCode.UPSTREAM_UNAVAILABLE,
				HolidayApiException.Reason.UPSTREAM_FAILURE, ErrorCode.UPSTREAM_FAILURE,
				HolidayApiException.Reason.MALFORMED_RESPONSE, ErrorCode.UPSTREAM_FAILURE
		);

		expectedCodes.forEach((reason, expectedCode) -> {
			ResponseEntity<ApiErrorResponse> response = handler.handleHolidayApiException(
					new HolidayApiException(reason, "test")
			);
			assertThat(response.getStatusCode()).isEqualTo(expectedCode.status());
			assertThat(response.getBody()).isNotNull().satisfies(body -> {
				assertThat(body.code()).isEqualTo(expectedCode.name());
				assertThat(body.message()).isEqualTo(expectedCode.message());
				assertThat(body.traceId()).hasSize(8);
			});
		});
	}

	@Test
	void mapsWeatherReasonsWithoutChangingTheErrorBodyContract() {
		Map<WeatherApiException.Reason, ErrorCode> expectedCodes = Map.of(
				WeatherApiException.Reason.CONFIGURATION, ErrorCode.UPSTREAM_UNAVAILABLE,
				WeatherApiException.Reason.RATE_LIMITED, ErrorCode.UPSTREAM_UNAVAILABLE,
				WeatherApiException.Reason.UPSTREAM_FAILURE, ErrorCode.UPSTREAM_FAILURE,
				WeatherApiException.Reason.MALFORMED_RESPONSE, ErrorCode.UPSTREAM_FAILURE,
				WeatherApiException.Reason.NO_FORECAST, ErrorCode.UPSTREAM_FAILURE
		);

		expectedCodes.forEach((reason, expectedCode) -> {
			ResponseEntity<ApiErrorResponse> response = handler.handleWeatherApiException(
					new WeatherApiException(reason, "test")
			);
			assertThat(response.getStatusCode()).isEqualTo(expectedCode.status());
			assertThat(response.getBody()).isNotNull().satisfies(body -> {
				assertThat(body.code()).isEqualTo(expectedCode.name());
				assertThat(body.message()).isEqualTo(expectedCode.message());
				assertThat(body.traceId()).hasSize(8);
			});
		});
	}

	@Test
	void exposesExpectedStatusesForFrameworkErrors() {
		assertThat(ErrorCode.RESOURCE_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorCode.METHOD_NOT_ALLOWED.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(ErrorCode.INVALID_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
