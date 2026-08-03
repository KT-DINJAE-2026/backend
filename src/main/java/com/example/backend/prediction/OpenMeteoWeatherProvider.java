package com.example.backend.prediction;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.StopEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenMeteoWeatherProvider implements WeatherProvider {

	private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);
	private static final Pattern WEATHER_CODE = Pattern.compile("\\\"weather_code\\\"\\s*:\\s*(-?\\d+)");

	private final AppProperties.Prediction properties;
	private final Clock clock;
	private final HttpClient httpClient;
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	public OpenMeteoWeatherProvider(AppProperties appProperties, Clock clock) {
		this.properties = appProperties.getPrediction();
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.getWeatherRequestTimeout())
				.build();
	}

	@Override
	public String currentWeather(StopEntity stop) {
		String fallback = properties.getDefaultWeather();
		if (!properties.isWeatherEnabled() || stop.getLatitude() == null || stop.getLongitude() == null) {
			return fallback;
		}

		double latitude = roundedCoordinate(stop.getLatitude());
		double longitude = roundedCoordinate(stop.getLongitude());
		String key = String.format(Locale.ROOT, "%.1f:%.1f", latitude, longitude);
		Instant now = clock.instant();
		CacheEntry current = cache.get(key);
		if (current != null && current.expiresAt().isAfter(now)) {
			return current.weather();
		}

		String weather = fetchOrFallback(latitude, longitude, fallback);
		Duration ttl = properties.getWeatherCacheTtl();
		if (!ttl.isZero() && !ttl.isNegative()) {
			cache.put(key, new CacheEntry(weather, now.plus(ttl)));
		}
		return weather;
	}

	private String fetchOrFallback(double latitude, double longitude, String fallback) {
		try {
			HttpRequest request = HttpRequest.newBuilder(requestUri(latitude, longitude))
					.timeout(properties.getWeatherRequestTimeout())
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> response = httpClient.send(
					request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
			);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}
			Matcher matcher = WEATHER_CODE.matcher(response.body());
			if (!matcher.find()) {
				throw new IllegalStateException("weather_code missing");
			}
			return wmoToLabel(Integer.parseInt(matcher.group(1)));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("Open-Meteo request interrupted; using fallback weather={}", fallback);
			return fallback;
		} catch (Exception exception) {
			log.warn(
					"Open-Meteo request failed; using fallback weather={} cause={}",
					fallback,
					exception.getClass().getSimpleName()
			);
			return fallback;
		}
	}

	private URI requestUri(double latitude, double longitude) {
		String baseUrl = properties.getWeatherBaseUrl().replaceAll("/+$", "");
		String query = "latitude=" + encode(String.format(Locale.ROOT, "%.1f", latitude))
				+ "&longitude=" + encode(String.format(Locale.ROOT, "%.1f", longitude))
				+ "&current=weather_code&timezone=Asia%2FSeoul";
		return URI.create(baseUrl + "?" + query);
	}

	static String wmoToLabel(int code) {
		if (code >= 0 && code <= 1) {
			return "맑음";
		}
		if (code == 2) {
			return "구름많음";
		}
		if (code == 3) {
			return "흐림";
		}
		if (code >= 45 && code <= 48) {
			return "안개";
		}
		if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
			return "비";
		}
		if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) {
			return "눈";
		}
		if (code >= 95 && code <= 99) {
			return "뇌우";
		}
		throw new IllegalArgumentException("지원하지 않는 WMO weather code: " + code);
	}

	private static double roundedCoordinate(BigDecimal coordinate) {
		return Math.round(coordinate.doubleValue() * 10.0) / 10.0;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private record CacheEntry(String weather, Instant expiresAt) {
	}
}
