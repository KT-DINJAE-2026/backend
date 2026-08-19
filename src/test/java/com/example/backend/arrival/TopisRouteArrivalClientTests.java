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

class TopisRouteArrivalClientTests {

	private final AtomicReference<String> responseBody = new AtomicReference<>();
	private final AtomicReference<String> rawQuery = new AtomicReference<>();

	private HttpServer server;
	private AppProperties appProperties;
	private TopisRouteArrivalClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/api/rest/arrive/getArrInfoByRouteAll", this::respond);
		server.start();

		appProperties = new AppProperties();
		appProperties.getTopis().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/rest");
		appProperties.getTopis().setServiceKey("test+/=");
		Clock clock = Clock.fixed(Instant.parse("2026-08-19T05:30:00Z"), ZoneId.of("Asia/Seoul"));
		client = new TopisRouteArrivalClient(appProperties, clock);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void mapsEveryStopAndItsTwoArrivalVehicles() {
		responseBody.set(successResponse());

		RouteArrivalSnapshot result = client.getRouteArrivals("100100129");

		assertThat(result.status()).isEqualTo(ArrivalLookupStatus.AVAILABLE);
		assertThat(result.providedAt().toString()).isEqualTo("2026-08-19T14:31:35+09:00");
		assertThat(result.stopsByOrder()).containsOnlyKeys(14, 15);
		assertThat(result.stopsByOrder().get(14).stopId()).isEqualTo("107000087");
		assertThat(result.arrivalsAt(14)).extracting(BusArrival::tripId)
				.containsExactly("107012516", "107012079");
		assertThat(result.arrivalsAt(14)).extracting(BusArrival::arrivalSeconds)
				.containsExactly(114, 322);
		assertThat(result.arrivalsAt(15)).extracting(BusArrival::arrivalSeconds)
				.containsExactly(210, 418);
		assertThat(rawQuery.get())
				.contains("serviceKey=test%2B%2F%3D")
				.contains("busRouteId=100100129");
	}

	@Test
	void mapsAuthenticationFailureInsideHttp200() {
		responseBody.set("""
				<ServiceResult><msgHeader><headerCd>7</headerCd>
				<headerMsg>Key인증실패: SERVICE KEY IS NOT REGISTERED ERROR.</headerMsg>
				</msgHeader></ServiceResult>
				""");

		assertThatThrownBy(() -> client.getRouteArrivals("100100129"))
				.isInstanceOf(TopisApiException.class)
				.satisfies(exception -> assertThat(((TopisApiException) exception).reason())
						.isEqualTo(TopisApiException.Reason.AUTHENTICATION));
	}

	@Test
	void mapsNoSearchResultToAnEmptySnapshot() {
		responseBody.set(apiErrorResponse("7", "검색 결과가 없습니다."));

		RouteArrivalSnapshot result = client.getRouteArrivals("100100129");

		assertThat(result.status()).isEqualTo(ArrivalLookupStatus.NO_ARRIVAL);
		assertThat(result.stopsByOrder()).isEmpty();
	}

	@Test
	void rejectsAnUnknownRouteMapping() {
		responseBody.set(apiErrorResponse("4", "노선을 찾을 수 없습니다."));

		assertThatThrownBy(() -> client.getRouteArrivals("999999999"))
				.isInstanceOf(TopisApiException.class)
				.satisfies(exception -> assertThat(((TopisApiException) exception).reason())
						.isEqualTo(TopisApiException.Reason.INVALID_MAPPING));
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
				  <msgHeader><headerCd>0</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg></msgHeader>
				  <msgBody>
				    <itemList>
				      <stId>107000087</stId><stNm>성북구청.성북경찰서</stNm><staOrd>14</staOrd>
				      <busRouteId>100100129</busRouteId><rtNm>1014</rtNm><dir>동묘앞 방면</dir>
				      <mkTm>2026-08-19 14:31:35.0</mkTm>
				      <vehId1>107012516</vehId1><plainNo1>서울74사1001</plainNo1><traTime1>114</traTime1>
				      <busType1>1</busType1><arrmsg1>1분54초후[1번째 전]</arrmsg1>
				      <vehId2>107012079</vehId2><plainNo2>서울74사1002</plainNo2><traTime2>322</traTime2>
				      <busType2>0</busType2><arrmsg2>5분22초후[3번째 전]</arrmsg2><deTourAt>00</deTourAt>
				    </itemList>
				    <itemList>
				      <stId>107000089</stId><stNm>보문역</stNm><staOrd>15</staOrd>
				      <busRouteId>100100129</busRouteId><rtNm>1014</rtNm><dir>동묘앞 방면</dir>
				      <mkTm>2026-08-19 14:31:35.0</mkTm>
				      <vehId1>107012516</vehId1><plainNo1>서울74사1001</plainNo1><traTime1>210</traTime1>
				      <busType1>1</busType1><arrmsg1>3분30초후</arrmsg1>
				      <vehId2>107012079</vehId2><plainNo2>서울74사1002</plainNo2><traTime2>418</traTime2>
				      <busType2>0</busType2><arrmsg2>6분58초후</arrmsg2><deTourAt>00</deTourAt>
				    </itemList>
				  </msgBody>
				</ServiceResult>
				""";
	}

	private static String apiErrorResponse(String code, String message) {
		return "<ServiceResult><msgHeader><headerCd>" + code + "</headerCd><headerMsg>"
				+ message + "</headerMsg></msgHeader></ServiceResult>";
	}
}
