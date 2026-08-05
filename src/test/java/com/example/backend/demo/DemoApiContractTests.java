package com.example.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 HTTP 경계를 통해 demo 프로필과 FE 핵심 계약을 함께 검증한다.
 * 필드 제거·enum 변경·CORS·Swagger 경로 변경 시 FE 연동 회귀를 잡는 테스트이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(DemoApiContractTests.FixedClockConfig.class)
class DemoApiContractTests {

	@LocalServerPort
	private int port;

	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		httpClient = HttpClient.newHttpClient();
	}

	@Test
	void contextAndSearchAreAvailableOverHttp() throws Exception {
		HttpResponse<String> context = get("/api/v1/stops/107000087/context");
		assertThat(context.statusCode()).isEqualTo(200);
		assertUtf8Json(context);
		assertThat(context.body())
				.contains("\"stopId\":\"107000087\"")
				.contains("\"stopId\":\"107000089\"")
				.contains("\"stopId\":\"100000147\"")
				.contains("\"routeNumber\":\"1014\"");

		String query = URLEncoder.encode("보문역", StandardCharsets.UTF_8);
		HttpResponse<String> search = get(
				"/api/v1/stops/search?originStopId=107000087&query=" + query
		);
		assertThat(search.statusCode()).isEqualTo(200);
		assertUtf8Json(search);
		assertThat(search.body())
				.contains("\"stopId\":\"107000089\"")
				.contains("\"routeId\":\"100100129\"")
				.contains("\"routeId\":\"100100031\"");
	}

	@Test
	void predictionSuccessMatchesTheServerContract() throws Exception {
		HttpResponse<String> response = postPrediction("107000087", "107000089");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("\"status\":\"SUCCESS\"")
				.contains("\"confidence\":\"MEDIUM\"")
				.contains("\"tripId\":\"mock-trip-100100129-1405\"")
				.contains("\"routeNumber\":\"1014\"")
				.contains("\"standingBurdenLevel\":\"LOW\"")
				.contains("\"segments\"")
				.doesNotContain("summaryMessage")
				.doesNotContain("description");
	}

	@Test
	void predictionInsufficientDataKeepsArrivalAndTravelFields() throws Exception {
		HttpResponse<String> response = postPrediction("107000087", "100000147");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("\"status\":\"INSUFFICIENT_DATA\"")
				.contains("\"reasonCode\":\"NOT_ENOUGH_HISTORICAL_SAMPLES\"")
				.contains("\"tripId\":\"mock-trip-100100129-1404\"")
				.contains("\"travelMinutes\":10")
				.doesNotContain("standingBurdenMinutes")
				.doesNotContain("segments");
	}

	@Test
	void noDirectRouteReturnsStructuredError() throws Exception {
		HttpResponse<String> response = postPrediction("107000087", "121009999");

		assertThat(response.statusCode()).isEqualTo(404);
		assertUtf8Json(response);
		assertThat(response.body())
				.contains("\"code\":\"NO_DIRECT_ROUTE\"")
				.contains("\"message\"")
				.contains("\"traceId\"");
	}

	@Test
	void frameworkErrorsUseTheFrontendErrorContract() throws Exception {
		HttpResponse<String> missingPath = get("/api/v1/not-found");
		assertError(missingPath, 404, "RESOURCE_NOT_FOUND");

		HttpRequest wrongMethodRequest = HttpRequest.newBuilder(uri("/api/v1/journeys/predictions"))
				.header("Accept", "application/json")
				.DELETE()
				.build();
		HttpResponse<String> wrongMethod = httpClient.send(
				wrongMethodRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
		assertError(wrongMethod, 405, "METHOD_NOT_ALLOWED");

		HttpRequest wrongContentTypeRequest = HttpRequest.newBuilder(uri("/api/v1/journeys/predictions"))
				.header("Accept", "application/json")
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString("not-json"))
				.build();
		HttpResponse<String> wrongContentType = httpClient.send(
				wrongContentTypeRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
		assertError(wrongContentType, 415, "UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void corsAllowsTheLocalFrontendOrigin() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/journeys/predictions"))
				.header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "POST")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
				.contains("http://localhost:5173");
	}

	@Test
	void openApiContainsAllFrontendEndpoints() throws Exception {
		HttpResponse<String> response = get("/v3/api-docs");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("/api/v1/stops/{stopId}/context")
				.contains("/api/v1/stops/search")
				.contains("/api/v1/journeys/predictions");
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
				.header("Accept", "application/json")
				.GET()
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> postPrediction(String originStopId, String destinationStopId) throws Exception {
		String body = """
				{"originStopId":"%s","destinationStopId":"%s"}
				""".formatted(originStopId, destinationStopId).strip();
		HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/journeys/predictions"))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private void assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertUtf8Json(response);
		assertThat(response.body())
				.contains("\"code\":\"" + code + "\"")
				.contains("\"message\"")
				.contains("\"traceId\"");
	}

	private void assertUtf8Json(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(contentType -> assertThat(contentType.toLowerCase())
						.contains("application/json")
						.contains("charset=utf-8"));
	}

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock demoClock() {
			// generatedAt을 고정해 시간대와 응답 예시가 실행 시각에 따라 흔들리지 않게 한다.
			return Clock.fixed(
					Instant.parse("2026-08-03T00:00:00Z"),
					ZoneId.of("Asia/Seoul")
			);
		}
	}
}
