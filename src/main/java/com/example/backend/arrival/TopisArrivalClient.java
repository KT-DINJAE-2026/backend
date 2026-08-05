package com.example.backend.arrival;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.BusVehicleType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 서울시 TOPIS {@code getArrInfoByRoute} API를 호출하고 응답 XML을 정규화한다.
 *
 * <p>demo 프로필에서는 고정 테스트 데이터만 사용하므로 이 빈을 만들지 않는다.
 * 운영 연동 시 공공데이터포털 인증키 승인 상태와 서울시 표준 ID 매핑을 먼저 확인해야 한다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "false", matchIfMissing = true)
public class TopisArrivalClient implements ArrivalClient {

	private static final Logger log = LoggerFactory.getLogger(TopisArrivalClient.class);
	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
	private static final Pattern ENCODED_SERVICE_KEY = Pattern.compile(
			"(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+"
	);
	private static final DateTimeFormatter PROVIDED_AT_FORMAT = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd HH:mm:ss")
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
			.optionalEnd()
			.toFormatter();

	private final AppProperties.Topis properties;
	private final Clock clock;
	private final HttpClient httpClient;

	public TopisArrivalClient(AppProperties appProperties, Clock clock) {
		this.properties = appProperties.getTopis();
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.getConnectTimeout())
				.build();
	}

	@Override
	public ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder) {
		OffsetDateTime requestedAt = OffsetDateTime.now(clock);
		if (!properties.isEnabled()) {
			return ArrivalLookupResult.empty(ArrivalLookupStatus.DISABLED, requestedAt);
		}
		if (properties.getServiceKey() == null || properties.getServiceKey().isBlank()) {
			throw new TopisApiException(
					TopisApiException.Reason.CONFIGURATION,
					"서울시 버스 API 인증키가 설정되지 않았습니다."
			);
		}

		HttpRequest request = HttpRequest.newBuilder(requestUri(stopId, routeId, stopOrder))
				.timeout(properties.getRequestTimeout())
				.header("Accept", "application/xml")
				.GET()
				.build();
		try {
			HttpResponse<String> response = httpClient.send(
					request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
			);
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				throw new TopisApiException(
						TopisApiException.Reason.AUTHENTICATION,
						"서울시 버스 API 인증 또는 활용 승인을 확인해 주세요."
				);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new TopisApiException(
						TopisApiException.Reason.UPSTREAM_FAILURE,
						"서울시 버스 API가 정상 응답을 반환하지 않았습니다."
				);
			}
			return parse(response.body(), requestedAt);
		} catch (TopisApiException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("TOPIS arrival request interrupted stopId={} routeId={}", stopId, routeId);
			throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API 호출이 중단되었습니다."
			);
		} catch (Exception exception) {
			log.warn(
					"TOPIS arrival request failed stopId={} routeId={} cause={}",
					stopId,
					routeId,
					exception.getClass().getSimpleName()
			);
			throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API 호출에 실패했습니다."
			);
		}
	}

	private URI requestUri(String stopId, String routeId, int stopOrder) {
		String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
		// 포털은 Encoding/Decoding 키를 모두 제공하므로 인증키만 별도 규칙으로 인코딩한다.
		String query = "serviceKey=" + encodeServiceKey(properties.getServiceKey())
				+ "&stId=" + encode(stopId)
				+ "&busRouteId=" + encode(routeId)
				+ "&ord=" + stopOrder;
		return URI.create(baseUrl + "/arrive/getArrInfoByRoute?" + query);
	}

	private ArrivalLookupResult parse(String body, OffsetDateTime fallbackTime) {
		try {
			Document document = secureDocumentBuilderFactory().newDocumentBuilder()
					.parse(new InputSource(new StringReader(body)));
			String headerCode = text(document.getDocumentElement(), "headerCd");
			if (!"0".equals(headerCode)) {
				return handleApiCode(
						headerCode,
						text(document.getDocumentElement(), "headerMsg"),
						fallbackTime
				);
			}

			OffsetDateTime providedAt = parseProvidedAt(
					text(document.getDocumentElement(), "mkTm"),
					fallbackTime
			);
			NodeList items = document.getElementsByTagName("itemList");
			if (items.getLength() == 0) {
				return ArrivalLookupResult.empty(ArrivalLookupStatus.NO_ARRIVAL, providedAt);
			}

			// getArrInfoByRoute는 한 itemList 안에 첫 번째·두 번째 차량 필드를 함께 내려준다.
			Element item = (Element) items.item(0);
			String routeId = text(item, "busRouteId");
			String routeNumber = firstNonBlank(text(item, "rtNm"), text(item, "busRouteAbrv"));
			String direction = text(item, "dir");
			boolean detour = "11".equals(text(item, "deTourAt"));
			List<BusArrival> arrivals = new ArrayList<>();
			addArrival(arrivals, item, 1, routeId, routeNumber, direction, detour);
			addArrival(arrivals, item, 2, routeId, routeNumber, direction, detour);
			ArrivalLookupStatus status = arrivals.isEmpty()
					? ArrivalLookupStatus.NO_ARRIVAL
					: ArrivalLookupStatus.AVAILABLE;
			return new ArrivalLookupResult(status, providedAt, arrivals);
		} catch (TopisApiException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new TopisApiException(
					TopisApiException.Reason.MALFORMED_RESPONSE,
					"서울시 버스 API 응답 형식을 해석할 수 없습니다."
			);
		}
	}

	private ArrivalLookupResult handleApiCode(
			String headerCode,
			String headerMessage,
			OffsetDateTime providedAt
	) {
		// 인증 실패도 HTTP 200으로 내려오는 경우가 있어 XML 헤더 메시지를 함께 검사한다.
		if (headerMessage.contains("Key인증실패") || headerMessage.contains("SERVICE KEY")) {
			throw new TopisApiException(
					TopisApiException.Reason.AUTHENTICATION,
					"서울시 버스 API 인증 또는 활용 승인을 확인해 주세요."
			);
		}
		return switch (headerCode) {
			case "6" -> ArrivalLookupResult.empty(ArrivalLookupStatus.TEMPORARILY_UNAVAILABLE, providedAt);
			case "8" -> ArrivalLookupResult.empty(ArrivalLookupStatus.SERVICE_ENDED, providedAt);
			case "3", "4" -> throw new TopisApiException(
					TopisApiException.Reason.INVALID_MAPPING,
					"서울시 버스 API의 정류소 또는 노선 ID 매핑을 확인해 주세요."
			);
			default -> throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API가 오류 코드를 반환했습니다."
			);
		};
	}

	private void addArrival(
			List<BusArrival> arrivals,
			Element item,
			int sequence,
			String routeId,
			String routeNumber,
			String direction,
			boolean detour
	) {
		String vehicleId = validIdentifier(text(item, "vehId" + sequence));
		String vehicleNumber = validIdentifier(text(item, "plainNo" + sequence));
		if (vehicleId == null && vehicleNumber == null) {
			return;
		}

		String tripId = vehicleId != null
				? vehicleId
				: routeId + ":" + vehicleNumber;
		// 데이터 시점에 따라 도착 초 필드가 달라 첫 번째 양수 값을 우선 사용한다.
		int arrivalSeconds = firstPositive(
				integer(item, "traTime" + sequence),
				integer(item, "exps" + sequence),
				integer(item, "kals" + sequence),
				integer(item, "neus" + sequence)
		);
		String busTypeCode = text(item, "busType" + sequence);
		arrivals.add(new BusArrival(
				tripId,
				routeId,
				routeNumber,
				direction,
				vehicleNumber,
				BusVehicleType.fromTopisCode(busTypeCode).displayName(),
				"1".equals(busTypeCode),
				arrivalSeconds,
				toArrivalMinutes(arrivalSeconds),
				text(item, "arrmsg" + sequence),
				"1".equals(text(item, "isLast" + sequence)),
				"1".equals(text(item, "full" + sequence)),
				detour
		));
	}

	private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
		// 외부 XML이 로컬 파일이나 네트워크 엔티티를 읽지 못하도록 XXE 기능을 모두 차단한다.
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		return factory;
	}

	private static String text(Element element, String tagName) {
		NodeList nodes = element.getElementsByTagName(tagName);
		return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
	}

	private static int integer(Element element, String tagName) {
		try {
			return Integer.parseInt(text(element, tagName));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private static int firstPositive(int... values) {
		for (int value : values) {
			if (value > 0) {
				return value;
			}
		}
		return 0;
	}

	private static int toArrivalMinutes(int seconds) {
		// FE는 분 단위로 표시하므로 남은 초를 버리지 않고 올림한다.
		return seconds <= 0 ? 0 : (seconds + 59) / 60;
	}

	private static String validIdentifier(String value) {
		return value == null || value.isBlank() || "0".equals(value) ? null : value;
	}

	private static String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	private static OffsetDateTime parseProvidedAt(String value, OffsetDateTime fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return LocalDateTime.parse(value, PROVIDED_AT_FORMAT).atZone(SEOUL_ZONE).toOffsetDateTime();
		} catch (RuntimeException exception) {
			// 제공 시각 형식이 달라도 도착정보 전체를 버리지 않고 요청 시각을 기준으로 삼는다.
			return fallback;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodeServiceKey(String value) {
		if (!value.contains("%")) {
			return encode(value);
		}
		// 이미 Encoding된 키를 다시 인코딩하면 %2B가 %252B로 바뀌어 인증에 실패한다.
		if (!ENCODED_SERVICE_KEY.matcher(value).matches()) {
			throw new TopisApiException(
					TopisApiException.Reason.CONFIGURATION,
					"서울시 버스 API Encoding 인증키 형식을 확인해 주세요."
			);
		}
		return value;
	}
}
