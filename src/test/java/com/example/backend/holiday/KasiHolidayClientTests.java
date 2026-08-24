package com.example.backend.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.DefaultResourceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 로컬 HTTP 서버로 공휴일 XML 파싱, 인증키 처리와 연도 캐시를 검증한다. */
class KasiHolidayClientTests {

	private final AtomicReference<String> responseBody = new AtomicReference<>();
	private final AtomicReference<String> rawQuery = new AtomicReference<>();
	private final AtomicInteger calls = new AtomicInteger();

	private HttpServer server;
	private AppProperties appProperties;
	private KasiHolidayClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/getRestDeInfo", this::respond);
		server.start();

		appProperties = new AppProperties();
		appProperties.getHoliday().setBaseUrl("http://localhost:" + server.getAddress().getPort());
		appProperties.getHoliday().setServiceKey("test+/=");
		Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		client = new KasiHolidayClient(appProperties, clock, new DefaultResourceLoader());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void loadsAndCachesHolidayDatesByYear() {
		responseBody.set(successResponse());

		assertThat(client.isHoliday(LocalDate.of(2026, 8, 15))).isTrue();
		assertThat(client.isHoliday(LocalDate.of(2026, 8, 16))).isFalse();

		assertThat(calls).hasValue(1);
		assertThat(rawQuery.get())
				.contains("ServiceKey=test%2B%2F%3D")
				.contains("solYear=2026")
				.contains("pageNo=1")
				.contains("numOfRows=100");
	}

	@Test
	void mapsAuthenticationErrorInsideSuccessfulHttpResponse() {
		responseBody.set("""
				<OpenAPI_ServiceResponse>
				  <cmmMsgHeader>
				    <returnReasonCode>30</returnReasonCode>
				    <returnAuthMsg>SERVICE KEY IS NOT REGISTERED ERROR.</returnAuthMsg>
				  </cmmMsgHeader>
				</OpenAPI_ServiceResponse>
				""");

		assertThatThrownBy(() -> client.isHoliday(LocalDate.of(2026, 8, 15)))
				.isInstanceOf(HolidayApiException.class)
				.satisfies(exception -> assertThat(((HolidayApiException) exception).reason())
						.isEqualTo(HolidayApiException.Reason.AUTHENTICATION));
	}

	@Test
	void mapsPortalRateLimitSeparatelyFromAuthentication() {
		// 한도 초과는 내장 목록 폴백 대상이라, 여기서는 폴백을 끄고 원인 매핑만 검증한다.
		appProperties.getHoliday().setFallbackResource("");
		responseBody.set("""
				<OpenAPI_ServiceResponse>
				  <cmmMsgHeader>
				    <returnReasonCode>22</returnReasonCode>
				    <returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>
				  </cmmMsgHeader>
				</OpenAPI_ServiceResponse>
				""");

		assertThatThrownBy(() -> client.isHoliday(LocalDate.of(2026, 8, 15)))
				.isInstanceOf(HolidayApiException.class)
				.satisfies(exception -> assertThat(((HolidayApiException) exception).reason())
						.isEqualTo(HolidayApiException.Reason.RATE_LIMITED));
	}

	@Test
	void rejectsMissingServiceKeyBeforeSendingRequest() {
		appProperties.getHoliday().setServiceKey("");

		assertThatThrownBy(() -> client.isHoliday(LocalDate.of(2026, 8, 15)))
				.isInstanceOf(HolidayApiException.class)
				.satisfies(exception -> assertThat(((HolidayApiException) exception).reason())
						.isEqualTo(HolidayApiException.Reason.CONFIGURATION));
		assertThat(calls).hasValue(0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		calls.incrementAndGet();
		rawQuery.set(exchange.getRequestURI().getRawQuery());
		byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static String successResponse() {
		return """
				<response>
				  <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
				  <body>
				    <items>
				      <item>
				        <dateKind>01</dateKind><dateName>광복절</dateName>
				        <isHoliday>Y</isHoliday><locdate>20260815</locdate><seq>1</seq>
				      </item>
				    </items>
				    <numOfRows>100</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount>
				  </body>
				</response>
				""";
	}
}
