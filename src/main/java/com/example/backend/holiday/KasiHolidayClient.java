package com.example.backend.holiday;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.example.backend.config.AppProperties;
import com.example.backend.publicdata.PublicDataServiceKeyEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 한국천문연구원 특일 정보의 연도별 공휴일을 조회하고 장시간 재사용한다.
 *
 * <p>KASI API(apis.data.go.kr)는 일부 클라우드 대역을 간헐 차단한다(2026-08-24 Cloud Run에서
 * 연결 타임아웃 실측). API 호출이 실패하면 같은 API에서 미리 받아 저장소에 내장한 목록으로
 * 폴백하고, 폴백 결과는 짧게 캐시해 API가 복구되면 실데이터로 자동 복귀한다.</p>
 */
@Component
public class KasiHolidayClient implements HolidayProvider {

	private static final Logger log = LoggerFactory.getLogger(KasiHolidayClient.class);
	private static final DateTimeFormatter API_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final AppProperties.Holiday properties;
	private final Clock clock;
	private final HttpClient httpClient;
	private final ResourceLoader resourceLoader;
	private final Map<Integer, CacheEntry> cache = new ConcurrentHashMap<>();
	private volatile Map<Integer, Set<LocalDate>> fallbackByYear;

	public KasiHolidayClient(AppProperties appProperties, Clock clock, ResourceLoader resourceLoader) {
		this.properties = appProperties.getHoliday();
		this.clock = clock;
		this.resourceLoader = resourceLoader;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.getConnectTimeout())
				.build();
	}

	@Override
	public boolean isHoliday(LocalDate date) {
		if (!properties.isEnabled()) {
			throw new HolidayApiException(
					HolidayApiException.Reason.CONFIGURATION,
					"공휴일 API가 비활성화되어 있습니다."
			);
		}
		if (properties.getServiceKey() == null || properties.getServiceKey().isBlank()) {
			throw new HolidayApiException(
					HolidayApiException.Reason.CONFIGURATION,
					"공휴일 API 인증키가 설정되지 않았습니다."
			);
		}

		// 공휴일은 차량마다 달라지지 않으므로 연도 목록을 한 번 받아 모든 예측 요청에서 공유한다.
		Duration cacheTtl = properties.getCacheTtl();
		Instant now = clock.instant();
		if (cacheTtl.isZero() || cacheTtl.isNegative()) {
			return loadYearOrFallback(date.getYear(), now, cacheTtl).dates().contains(date);
		}

		CacheEntry entry = cache.compute(date.getYear(), (year, current) -> {
			if (current != null && current.expiresAt().isAfter(now)) {
				return current;
			}
			return loadYearOrFallback(year, now, cacheTtl);
		});
		return entry.dates().contains(date);
	}

	/**
	 * API에서 연도 공휴일을 받되, 실패하면 내장 목록으로 대신한다. 폴백 결과는 짧은 TTL로
	 * 캐시해 매 요청이 연결 타임아웃을 기다리지 않게 하고, API 복구 시 실데이터로 되돌아간다.
	 */
	private CacheEntry loadYearOrFallback(int year, Instant now, Duration cacheTtl) {
		try {
			return new CacheEntry(loadYear(year), now.plus(cacheTtl));
		} catch (HolidayApiException exception) {
			// 인증·구성 오류는 고쳐야 할 설정 문제라 폴백으로 가리지 않는다.
			if (!fallbackEligible(exception.reason())) {
				throw exception;
			}
			Set<LocalDate> fallback = fallbackDates(year);
			if (fallback == null) {
				throw exception;
			}
			log.warn(
					"공휴일 API 실패로 내장 목록을 사용합니다. year={} retryAfter={}",
					year,
					properties.getFailureCacheTtl()
			);
			return new CacheEntry(fallback, now.plus(properties.getFailureCacheTtl()));
		}
	}

	/** 통신 실패·호출 한도·비정상 응답처럼 일시적 가용성 문제만 폴백 대상으로 삼는다. */
	private static boolean fallbackEligible(HolidayApiException.Reason reason) {
		return reason == HolidayApiException.Reason.UPSTREAM_FAILURE
				|| reason == HolidayApiException.Reason.RATE_LIMITED
				|| reason == HolidayApiException.Reason.MALFORMED_RESPONSE;
	}

	/** 내장 목록에서 해당 연도 공휴일을 찾는다. 자료가 없거나 연도가 빠져 있으면 {@code null}. */
	private Set<LocalDate> fallbackDates(int year) {
		String location = properties.getFallbackResource();
		if (location == null || location.isBlank()) {
			return null;
		}
		Map<Integer, Set<LocalDate>> loaded = fallbackByYear;
		if (loaded == null) {
			synchronized (this) {
				loaded = fallbackByYear;
				if (loaded == null) {
					loaded = readFallbackResource(location);
					fallbackByYear = loaded;
				}
			}
		}
		return loaded.get(year);
	}

	private Map<Integer, Set<LocalDate>> readFallbackResource(String location) {
		Resource resource = resourceLoader.getResource(location);
		Map<Integer, Set<LocalDate>> byYear = new HashMap<>();
		try (InputStream inputStream = resource.getInputStream();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				// 형식: yyyyMMdd,공휴일명 (src/main/resources/holiday/README.md)
				LocalDate date = LocalDate.parse(line.split(",", 2)[0].trim(), API_DATE);
				byYear.computeIfAbsent(date.getYear(), ignored -> new HashSet<>()).add(date);
			}
		} catch (IOException | DateTimeParseException exception) {
			// 폴백 자료 문제는 원래의 API 예외를 가리지 않도록 빈 자료로 취급한다.
			log.warn("내장 공휴일 목록을 읽을 수 없습니다. resource={} cause={}",
					location, exception.getClass().getSimpleName());
			return Map.of();
		}
		byYear.replaceAll((ignored, dates) -> Set.copyOf(dates));
		return Map.copyOf(byYear);
	}

	private Set<LocalDate> loadYear(int year) {
		// solMonth를 생략하면 한 번의 호출로 해당 연도 전체 공휴일을 받을 수 있다.
		HttpRequest request = HttpRequest.newBuilder(requestUri(year))
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
				throw new HolidayApiException(
						HolidayApiException.Reason.UPSTREAM_FAILURE,
						"공휴일 API가 정상 응답을 반환하지 않았습니다."
				);
			}
			return parse(response.body());
		} catch (HolidayApiException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new HolidayApiException(
					HolidayApiException.Reason.UPSTREAM_FAILURE,
					"공휴일 API 호출이 중단되었습니다."
			);
		} catch (Exception exception) {
			log.warn("Holiday API request failed year={} cause={}", year, exception.getClass().getSimpleName());
			throw new HolidayApiException(
					HolidayApiException.Reason.UPSTREAM_FAILURE,
					"공휴일 API 호출에 실패했습니다."
			);
		}
	}

	private URI requestUri(int year) {
		String serviceKey;
		try {
			serviceKey = PublicDataServiceKeyEncoder.encode(properties.getServiceKey());
		} catch (IllegalArgumentException exception) {
			throw new HolidayApiException(
					HolidayApiException.Reason.CONFIGURATION,
					"공휴일 API 인증키 형식을 확인해 주세요."
			);
		}
		String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
		return URI.create(baseUrl + "/getRestDeInfo"
				+ "?ServiceKey=" + serviceKey
				+ "&solYear=" + year
				+ "&pageNo=1&numOfRows=100");
	}

	private Set<LocalDate> parse(String body) {
		try {
			Document document = secureDocumentBuilderFactory().newDocumentBuilder()
					.parse(new InputSource(new StringReader(body)));
			Element root = document.getDocumentElement();
			String resultCode = firstNonBlank(text(root, "resultCode"), text(root, "returnReasonCode"));
			String resultMessage = firstNonBlank(text(root, "resultMsg"), text(root, "returnAuthMsg"));
			if (!"00".equals(resultCode)) {
				handleApiError(resultCode, resultMessage);
			}

			Set<LocalDate> holidays = new HashSet<>();
			NodeList items = document.getElementsByTagName("item");
			for (int index = 0; index < items.getLength(); index++) {
				Element item = (Element) items.item(index);
				// 기념일·절기도 함께 내려올 수 있으므로 공공기관 휴일 Y인 날짜만 모델 피처로 사용한다.
				if (!"Y".equalsIgnoreCase(text(item, "isHoliday"))) {
					continue;
				}
				String value = text(item, "locdate");
				if (!value.isBlank()) {
					holidays.add(LocalDate.parse(value, API_DATE));
				}
			}
			return Set.copyOf(holidays);
		} catch (HolidayApiException exception) {
			throw exception;
		} catch (DateTimeParseException exception) {
			throw malformedResponse();
		} catch (Exception exception) {
			throw malformedResponse();
		}
	}

	private static void handleApiError(String resultCode, String resultMessage) {
		String message = resultMessage == null ? "" : resultMessage.toUpperCase();
		// 호출 한도는 키 자체의 문제와 달리 시간이 지나면 재시도할 수 있어 별도 원인으로 둔다.
		if (Set.of("22", "23").contains(resultCode)
				|| message.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) {
			throw new HolidayApiException(
					HolidayApiException.Reason.RATE_LIMITED,
					"공휴일 API 호출 한도를 초과했습니다."
			);
		}
		if (message.contains("SERVICE KEY")
				|| message.contains("AUTH")
				|| message.contains("ACCESS DENIED")
				|| Set.of("20", "29", "30", "31").contains(resultCode)) {
			throw authenticationFailure();
		}
		throw new HolidayApiException(
				HolidayApiException.Reason.UPSTREAM_FAILURE,
				"공휴일 API가 오류 코드를 반환했습니다."
		);
	}

	private static HolidayApiException authenticationFailure() {
		return new HolidayApiException(
				HolidayApiException.Reason.AUTHENTICATION,
				"공휴일 API 인증 또는 활용 승인을 확인해 주세요."
		);
	}

	private static HolidayApiException malformedResponse() {
		return new HolidayApiException(
				HolidayApiException.Reason.MALFORMED_RESPONSE,
				"공휴일 API 응답 형식을 해석할 수 없습니다."
		);
	}

	private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
		// 외부 XML이 로컬 파일이나 네트워크 엔티티를 읽지 못하도록 XXE 기능을 차단한다.
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

	private static String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	private record CacheEntry(Set<LocalDate> dates, Instant expiresAt) {
	}
}
