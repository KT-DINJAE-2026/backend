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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.BusVehicleType;
import com.example.backend.publicdata.PublicDataServiceKeyEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** TOPIS {@code getArrInfoByRouteAll}을 호출해 노선 전체 정류장의 첫·두 번째 도착 차량을 읽는다. */
@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "false", matchIfMissing = true)
public class TopisRouteArrivalClient implements RouteArrivalClient {

	private static final Logger log = LoggerFactory.getLogger(TopisRouteArrivalClient.class);
	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter PROVIDED_AT_FORMAT = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd HH:mm:ss")
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
			.optionalEnd()
			.toFormatter();

	private final AppProperties.Topis properties;
	private final Clock clock;
	private final HttpClient httpClient;

	public TopisRouteArrivalClient(AppProperties appProperties, Clock clock) {
		this.properties = appProperties.getTopis();
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.getConnectTimeout())
				.build();
	}

	@Override
	public RouteArrivalSnapshot getRouteArrivals(String routeId) {
		OffsetDateTime requestedAt = OffsetDateTime.now(clock);
		if (!properties.isEnabled()) {
			return RouteArrivalSnapshot.empty(ArrivalLookupStatus.DISABLED, requestedAt, routeId);
		}
		if (properties.getServiceKey() == null || properties.getServiceKey().isBlank()) {
			throw new TopisApiException(
					TopisApiException.Reason.CONFIGURATION,
					"서울시 버스 API 인증키가 설정되지 않았습니다."
			);
		}

		HttpRequest request = HttpRequest.newBuilder(requestUri(routeId))
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
				throw authenticationFailure();
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new TopisApiException(
						TopisApiException.Reason.UPSTREAM_FAILURE,
						"서울시 버스 API가 정상 응답을 반환하지 않았습니다."
				);
			}
			return parse(response.body(), routeId, requestedAt);
		} catch (TopisApiException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API 호출이 중단되었습니다."
			);
		} catch (Exception exception) {
			log.warn("TOPIS route arrival request failed routeId={} cause={}", routeId,
					exception.getClass().getSimpleName());
			throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API 호출에 실패했습니다."
			);
		}
	}

	private URI requestUri(String routeId) {
		String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
		String query = "serviceKey=" + encodeServiceKey(properties.getServiceKey())
				+ "&busRouteId=" + encode(routeId);
		return URI.create(baseUrl + "/arrive/getArrInfoByRouteAll?" + query);
	}

	RouteArrivalSnapshot parse(String body, String requestedRouteId, OffsetDateTime fallbackTime) {
		try {
			Document document = secureDocumentBuilderFactory().newDocumentBuilder()
					.parse(new InputSource(new StringReader(body)));
			Element root = document.getDocumentElement();
			String headerCode = text(root, "headerCd");
			String headerMessage = text(root, "headerMsg");
			if (!"0".equals(headerCode)) {
				return handleApiCode(headerCode, headerMessage, requestedRouteId, fallbackTime);
			}

			NodeList items = document.getElementsByTagName("itemList");
			if (items.getLength() == 0) {
				return RouteArrivalSnapshot.empty(
						ArrivalLookupStatus.NO_ARRIVAL,
						fallbackTime,
						requestedRouteId
				);
			}

			Map<Integer, StopArrivalSnapshot> stops = new LinkedHashMap<>();
			OffsetDateTime providedAt = fallbackTime;
			for (int index = 0; index < items.getLength(); index++) {
				Element item = (Element) items.item(index);
				int stopOrder = integer(item, "staOrd");
				String stopId = text(item, "stId");
				if (stopOrder <= 0 || stopId.isBlank()) {
					continue;
				}
				providedAt = parseProvidedAt(text(item, "mkTm"), providedAt);
				stops.putIfAbsent(stopOrder, new StopArrivalSnapshot(
						stopId,
						text(item, "stNm"),
						stopOrder,
						arrivals(item, requestedRouteId)
				));
			}
			ArrivalLookupStatus status = stops.values().stream()
					.anyMatch(stop -> !stop.arrivals().isEmpty())
					? ArrivalLookupStatus.AVAILABLE
					: ArrivalLookupStatus.NO_ARRIVAL;
			return new RouteArrivalSnapshot(status, providedAt, requestedRouteId, stops);
		} catch (TopisApiException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new TopisApiException(
					TopisApiException.Reason.MALFORMED_RESPONSE,
					"서울시 버스 API 응답 형식을 해석할 수 없습니다."
			);
		}
	}

	private RouteArrivalSnapshot handleApiCode(
			String headerCode,
			String headerMessage,
			String routeId,
			OffsetDateTime providedAt
	) {
		if (headerMessage.contains("Key인증실패") || headerMessage.contains("SERVICE KEY")) {
			throw authenticationFailure();
		}
		return switch (headerCode) {
			case "6" -> throw new TopisApiException(
					TopisApiException.Reason.UPSTREAM_FAILURE,
					"서울시 버스 API가 실시간 정보를 제공하지 못했습니다."
			);
			case "7" -> RouteArrivalSnapshot.empty(ArrivalLookupStatus.NO_ARRIVAL, providedAt, routeId);
			case "8" -> RouteArrivalSnapshot.empty(ArrivalLookupStatus.SERVICE_ENDED, providedAt, routeId);
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

	private List<BusArrival> arrivals(Element item, String fallbackRouteId) {
		String routeId = firstNonBlank(text(item, "busRouteId"), fallbackRouteId);
		String routeNumber = firstNonBlank(text(item, "rtNm"), text(item, "busRouteAbrv"));
		String direction = text(item, "dir");
		boolean detour = "11".equals(text(item, "deTourAt"));
		List<BusArrival> arrivals = new ArrayList<>(2);
		addArrival(arrivals, item, 1, routeId, routeNumber, direction, detour);
		addArrival(arrivals, item, 2, routeId, routeNumber, direction, detour);
		return arrivals;
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
		String tripId = vehicleId != null ? vehicleId : routeId + ":" + vehicleNumber;
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
				trueValue(text(item, "full" + sequence)),
				detour
		));
	}

	private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
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
		return seconds <= 0 ? 0 : (seconds + 59) / 60;
	}

	private static boolean trueValue(String value) {
		return "1".equals(value) || "Y".equalsIgnoreCase(value);
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
			return fallback;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodeServiceKey(String value) {
		try {
			return PublicDataServiceKeyEncoder.encode(value);
		} catch (IllegalArgumentException exception) {
			throw new TopisApiException(
					TopisApiException.Reason.CONFIGURATION,
					"서울시 버스 API Encoding 인증키 형식을 확인해 주세요."
			);
		}
	}

	private static TopisApiException authenticationFailure() {
		return new TopisApiException(
				TopisApiException.Reason.AUTHENTICATION,
				"서울시 버스 API 인증 또는 활용 승인을 확인해 주세요."
		);
	}
}
