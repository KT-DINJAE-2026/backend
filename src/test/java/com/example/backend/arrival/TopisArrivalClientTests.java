package com.example.backend.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 네트워크 대신 로컬 HTTP 서버로 TOPIS 요청 URI와 XML 변환 계약을 검증한다. */
class TopisArrivalClientTests {

	private final AtomicReference<String> responseBody = new AtomicReference<>();
	private final AtomicReference<String> rawQuery = new AtomicReference<>();

	private HttpServer server;
	private AppProperties appProperties;
	private TopisArrivalClient client;

	@BeforeEach
	void setUp() throws IOException {
		// 포털 응답과 raw query를 모두 통제해 인증키 이중 인코딩 회귀를 함께 검증한다.
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/api/rest/arrive/getArrInfoByRoute", this::respond);
		server.start();

		appProperties = new AppProperties();
		appProperties.getTopis().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/rest");
		appProperties.getTopis().setServiceKey("test+/=");
		Clock clock = Clock.fixed(Instant.parse("2026-08-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));
		client = new TopisArrivalClient(appProperties, clock);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void mapsTwoArrivalsAndEncodesTheServiceKey() {
		responseBody.set(successResponse());

		ArrivalLookupResult result = client.getArrivals("121000019", "100100027", 35);

		assertThat(result.status()).isEqualTo(ArrivalLookupStatus.AVAILABLE);
		assertThat(result.providedAt().toString()).isEqualTo("2026-08-03T12:00+09:00");
		assertThat(result.arrivals()).hasSize(2);
		assertThat(result.arrivals().getFirst())
				.satisfies(arrival -> {
					assertThat(arrival.tripId()).isEqualTo("111000001");
					assertThat(arrival.routeId()).isEqualTo("100100027");
					assertThat(arrival.routeNumber()).isEqualTo("148");
					assertThat(arrival.arrivalSeconds()).isEqualTo(301);
					assertThat(arrival.arrivalMinutes()).isEqualTo(6);
					assertThat(arrival.lowFloor()).isTrue();
					assertThat(arrival.vehicleType()).isEqualTo("저상버스");
				});
		assertThat(result.arrivals().get(1))
				.satisfies(arrival -> {
					assertThat(arrival.tripId()).isEqualTo("111000002");
					assertThat(arrival.arrivalMinutes()).isEqualTo(1);
					assertThat(arrival.lowFloor()).isFalse();
					assertThat(arrival.vehicleType()).isEqualTo("일반버스");
				});
		assertThat(rawQuery.get())
				.contains("serviceKey=test%2B%2F%3D")
				.contains("stId=121000019")
				.contains("busRouteId=100100027")
				.contains("ord=35");
	}

	@Test
	void mapsServiceEndedWithoutArrivals() {
		responseBody.set("""
				<ServiceResult>
				  <msgHeader><headerCd>8</headerCd><headerMsg>운행 종료되었습니다.</headerMsg></msgHeader>
				</ServiceResult>
				""");

		ArrivalLookupResult result = client.getArrivals("121000019", "100100027", 35);

		assertThat(result.status()).isEqualTo(ArrivalLookupStatus.SERVICE_ENDED);
		assertThat(result.arrivals()).isEmpty();
	}

	@Test
	void doesNotEncodeAnAlreadyEncodedServiceKeyTwice() {
		appProperties.getTopis().setServiceKey("encoded%2Bkey%2Fvalue%3D%3D");
		responseBody.set(successResponse());

		client.getArrivals("121000019", "100100027", 35);

		assertThat(rawQuery.get())
				.contains("serviceKey=encoded%2Bkey%2Fvalue%3D%3D")
				.doesNotContain("%252B", "%252F", "%253D");
	}

	@Test
	void mapsAuthenticationFailureReturnedInsideASuccessfulHttpResponse() {
		responseBody.set("""
				<ServiceResult>
				  <msgHeader>
				    <headerCd>7</headerCd>
				    <headerMsg>Key인증실패: SERVICE KEY IS NOT REGISTERED ERROR.</headerMsg>
				  </msgHeader>
				</ServiceResult>
				""");

		assertThatThrownBy(() -> client.getArrivals("121000019", "100100027", 35))
				.isInstanceOf(TopisApiException.class)
				.satisfies(exception -> assertThat(((TopisApiException) exception).reason())
						.isEqualTo(TopisApiException.Reason.AUTHENTICATION));
	}

	@Test
	void rejectsMissingServiceKeyBeforeSendingARequest() {
		appProperties.getTopis().setServiceKey("");

		assertThatThrownBy(() -> client.getArrivals("121000019", "100100027", 35))
				.isInstanceOf(TopisApiException.class)
				.satisfies(exception -> assertThat(((TopisApiException) exception).reason())
						.isEqualTo(TopisApiException.Reason.CONFIGURATION));
	}

	private void respond(HttpExchange exchange) throws IOException {
		rawQuery.set(exchange.getRequestURI().getRawQuery());
		byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/xml;charset=UTF-8");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static String successResponse() {
		return """
				<ServiceResult>
				  <msgHeader>
				    <headerCd>0</headerCd>
				    <headerMsg>정상적으로 처리되었습니다.</headerMsg>
				  </msgHeader>
				  <msgBody>
				    <itemList>
				      <busRouteId>100100027</busRouteId>
				      <rtNm>148</rtNm>
				      <dir>번동 방면</dir>
				      <mkTm>2026-08-03 12:00:00.0</mkTm>
				      <deTourAt>00</deTourAt>
				      <vehId1>111000001</vehId1>
				      <plainNo1>서울74사1001</plainNo1>
				      <traTime1>301</traTime1>
				      <busType1>1</busType1>
				      <arrmsg1>5분1초후[2번째 전]</arrmsg1>
				      <isLast1>0</isLast1>
				      <full1>0</full1>
				      <vehId2>111000002</vehId2>
				      <plainNo2>서울74사1002</plainNo2>
				      <traTime2>60</traTime2>
				      <busType2>0</busType2>
				      <arrmsg2>1분후[1번째 전]</arrmsg2>
				      <isLast2>1</isLast2>
				      <full2>1</full2>
				    </itemList>
				  </msgBody>
				</ServiceResult>
				""";
	}
}
