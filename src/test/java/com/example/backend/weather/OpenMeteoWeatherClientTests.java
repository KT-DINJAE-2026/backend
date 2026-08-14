package com.example.backend.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 로컬 HTTP 서버로 시간별 예보 선택, WMO 변환과 격자 캐시를 검증한다. */
class OpenMeteoWeatherClientTests {

	private final AtomicReference<String> responseBody = new AtomicReference<>();
	private final AtomicReference<String> rawQuery = new AtomicReference<>();
	private final AtomicInteger responseStatus = new AtomicInteger(200);
	private final AtomicInteger calls = new AtomicInteger();

	private HttpServer server;
	private OpenMeteoWeatherClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/forecast", this::respond);
		server.start();

		AppProperties properties = new AppProperties();
		properties.getWeather().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/forecast");
		Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		client = new OpenMeteoWeatherClient(properties, clock, new ObjectMapper());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void returnsWeatherAtTheSeoulBoardingHourAndCachesTheGridDay() {
		responseBody.set(successResponse());

		String first = client.weatherAt(
				new BigDecimal("37.61"),
				new BigDecimal("127.01"),
				OffsetDateTime.parse("2026-08-14T16:30:00Z")
		);
		String second = client.weatherAt(
				new BigDecimal("37.62"),
				new BigDecimal("127.02"),
				OffsetDateTime.parse("2026-08-15T02:10:00+09:00")
		);
		String boundary = client.weatherAt(
				new BigDecimal("37.65"),
				new BigDecimal("127.05"),
				OffsetDateTime.parse("2026-08-15T00:10:00+09:00")
		);

		assertThat(first).isEqualTo("비");
		assertThat(second).isEqualTo("눈");
		assertThat(boundary).isEqualTo("맑음");
		assertThat(calls).hasValue(1);
		assertThat(rawQuery.get())
				.contains("latitude=37.6")
				.contains("longitude=127.0")
				.contains("hourly=weather_code")
				.contains("timezone=Asia%2FSeoul")
				.contains("start_date=2026-08-15")
				.contains("end_date=2026-08-15");
	}

	@Test
	void mapsRateLimitResponse() {
		responseStatus.set(429);
		responseBody.set("{\"reason\":\"rate limited\"}");

		assertThatThrownBy(() -> client.weatherAt(
				new BigDecimal("37.6"),
				new BigDecimal("127.0"),
				OffsetDateTime.parse("2026-08-15T01:00:00+09:00")
		)).isInstanceOf(WeatherApiException.class)
				.satisfies(exception -> assertThat(((WeatherApiException) exception).reason())
						.isEqualTo(WeatherApiException.Reason.RATE_LIMITED));
	}

	@Test
	void rejectsMissingStopCoordinates() {
		assertThatThrownBy(() -> client.weatherAt(
				null,
				new BigDecimal("127.0"),
				OffsetDateTime.parse("2026-08-15T01:00:00+09:00")
		)).isInstanceOf(WeatherApiException.class)
				.satisfies(exception -> assertThat(((WeatherApiException) exception).reason())
						.isEqualTo(WeatherApiException.Reason.CONFIGURATION));
		assertThat(calls).hasValue(0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		calls.incrementAndGet();
		rawQuery.set(exchange.getRequestURI().getRawQuery());
		byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
		exchange.sendResponseHeaders(responseStatus.get(), body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static String successResponse() {
		return """
				{
				  "latitude":37.6,
				  "longitude":127.0,
				  "timezone":"Asia/Seoul",
				  "hourly":{
				    "time":["2026-08-15T00:00","2026-08-15T01:00","2026-08-15T02:00"],
				    "weather_code":[0,95,75]
				  }
				}
				""";
	}
}
