package com.example.backend.weather;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.backend.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Open-Meteo 시간별 예보를 조회해 PMML의 다섯 날씨 범주로 변환한다. */
@Component
public class OpenMeteoWeatherClient implements WeatherProvider {

	private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherClient.class);
	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

	private final AppProperties.Weather properties;
	private final Clock clock;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

	public OpenMeteoWeatherClient(AppProperties appProperties, Clock clock, ObjectMapper objectMapper) {
		this.properties = appProperties.getWeather();
		this.clock = clock;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.getConnectTimeout())
				.build();
	}

	@Override
	public String weatherAt(
			BigDecimal latitude,
			BigDecimal longitude,
			OffsetDateTime boardingTime
	) {
		validateConfiguration(latitude, longitude, boardingTime);
		// Open-Meteo 응답도 Asia/Seoul로 요청하므로 조회 키와 비교 시각을 먼저 서울 시간으로 통일한다.
		ZonedDateTime seoulTime = boardingTime.atZoneSameInstant(SEOUL_ZONE);
		CacheKey key = new CacheKey(
				gridCoordinate(latitude),
				gridCoordinate(longitude),
				seoulTime.toLocalDate()
		);
		Map<Integer, String> hourlyWeather = getDailyForecast(key);
		String weather = hourlyWeather.get(seoulTime.getHour());
		if (weather == null) {
			throw new WeatherApiException(
					WeatherApiException.Reason.NO_FORECAST,
					"승차 예정 시각의 날씨 예보를 찾을 수 없습니다."
			);
		}
		return weather;
	}

	private Map<Integer, String> getDailyForecast(CacheKey key) {
		Duration cacheTtl = properties.getCacheTtl();
		if (cacheTtl.isZero() || cacheTtl.isNegative()) {
			return loadDailyForecast(key);
		}

		Instant now = clock.instant();
		// 하루 응답에 24시간 값이 모두 있으므로 차량·시간마다 재호출하지 않고 격자·날짜 단위로 공유한다.
		cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		CacheEntry entry = cache.compute(key, (ignored, current) -> {
			if (current != null && current.expiresAt().isAfter(now)) {
				return current;
			}
			return new CacheEntry(loadDailyForecast(key), now.plus(cacheTtl));
		});
		return entry.hourlyWeather();
	}

	private Map<Integer, String> loadDailyForecast(CacheKey key) {
		HttpRequest request = HttpRequest.newBuilder(requestUri(key))
				.timeout(properties.getRequestTimeout())
				.header("Accept", "application/json")
				.GET()
				.build();
		try {
			HttpResponse<String> response = httpClient.send(
					request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
			);
			if (response.statusCode() == 429) {
				throw new WeatherApiException(
						WeatherApiException.Reason.RATE_LIMITED,
						"날씨 API 호출 한도를 초과했습니다."
				);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new WeatherApiException(
						WeatherApiException.Reason.UPSTREAM_FAILURE,
						"날씨 API가 정상 응답을 반환하지 않았습니다."
				);
			}
			return parse(response.body(), key.date());
		} catch (WeatherApiException exception) {
			throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new WeatherApiException(
					WeatherApiException.Reason.UPSTREAM_FAILURE,
					"날씨 API 호출이 중단되었습니다."
			);
		} catch (Exception exception) {
			log.warn(
					"Weather API request failed latitude={} longitude={} date={} cause={}",
					key.latitude(), key.longitude(), key.date(), exception.getClass().getSimpleName()
			);
			throw new WeatherApiException(
					WeatherApiException.Reason.UPSTREAM_FAILURE,
					"날씨 API 호출에 실패했습니다."
			);
		}
	}

	private URI requestUri(CacheKey key) {
		String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
		String timezone = URLEncoder.encode(SEOUL_ZONE.getId(), StandardCharsets.UTF_8);
		// start/end 날짜를 같게 지정해 필요한 하루치 시간 배열만 내려받는다.
		return URI.create(baseUrl
				+ "?latitude=" + key.latitude().toPlainString()
				+ "&longitude=" + key.longitude().toPlainString()
				+ "&hourly=weather_code"
				+ "&timezone=" + timezone
				+ "&start_date=" + key.date()
				+ "&end_date=" + key.date());
	}

	private Map<Integer, String> parse(String body, LocalDate expectedDate) {
		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode times = root.path("hourly").path("time");
			JsonNode codes = root.path("hourly").path("weather_code");
			if (!times.isArray() || !codes.isArray() || times.size() != codes.size()) {
				throw malformedResponse();
			}

			Map<Integer, String> hourlyWeather = new HashMap<>();
			for (int index = 0; index < times.size(); index++) {
				if (times.get(index).isNull() || codes.get(index).isNull()) {
					continue;
				}
				LocalDateTime time = LocalDateTime.parse(times.get(index).stringValue())
						.truncatedTo(ChronoUnit.HOURS);
				if (!expectedDate.equals(time.toLocalDate())) {
					continue;
				}
				int code = codes.get(index).intValue();
				// 모델에는 숫자 WMO 코드가 아니라 학습 당시 사용한 한글 범주를 전달한다.
				hourlyWeather.put(time.getHour(), ModelWeatherCategory.fromWmoCode(code).label());
			}
			if (hourlyWeather.isEmpty()) {
				throw malformedResponse();
			}
			return Map.copyOf(hourlyWeather);
		} catch (WeatherApiException exception) {
			throw exception;
		} catch (DateTimeParseException | IllegalArgumentException exception) {
			throw malformedResponse();
		} catch (Exception exception) {
			throw malformedResponse();
		}
	}

	private void validateConfiguration(
			BigDecimal latitude,
			BigDecimal longitude,
			OffsetDateTime boardingTime
	) {
		if (!properties.isEnabled()) {
			throw new WeatherApiException(
					WeatherApiException.Reason.CONFIGURATION,
					"날씨 API가 비활성화되어 있습니다."
			);
		}
		if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
			throw new WeatherApiException(
					WeatherApiException.Reason.CONFIGURATION,
					"날씨 API 주소가 설정되지 않았습니다."
			);
		}
		if (latitude == null || longitude == null) {
			throw new WeatherApiException(
					WeatherApiException.Reason.CONFIGURATION,
					"승차 정류장의 좌표가 없습니다."
			);
		}
		if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
				|| latitude.compareTo(BigDecimal.valueOf(90)) > 0
				|| longitude.compareTo(BigDecimal.valueOf(-180)) < 0
				|| longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
			throw new WeatherApiException(
					WeatherApiException.Reason.CONFIGURATION,
					"승차 정류장의 좌표 범위가 올바르지 않습니다."
			);
		}
		if (boardingTime == null) {
			throw new WeatherApiException(
					WeatherApiException.Reason.CONFIGURATION,
					"승차 예정 시각이 없습니다."
			);
		}
	}

	private static BigDecimal gridCoordinate(BigDecimal coordinate) {
		// 학습 파이프라인의 Python round(x, 1)과 같은 HALF_EVEN 0.1도 격자를 사용한다.
		return coordinate.setScale(1, RoundingMode.HALF_EVEN);
	}

	private static WeatherApiException malformedResponse() {
		return new WeatherApiException(
				WeatherApiException.Reason.MALFORMED_RESPONSE,
				"날씨 API 응답 형식을 해석할 수 없습니다."
		);
	}

	private record CacheKey(BigDecimal latitude, BigDecimal longitude, LocalDate date) {
	}

	private record CacheEntry(Map<Integer, String> hourlyWeather, Instant expiresAt) {
	}
}
