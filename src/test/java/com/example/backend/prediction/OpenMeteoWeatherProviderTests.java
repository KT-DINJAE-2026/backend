package com.example.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.StopEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenMeteoWeatherProviderTests {

	private HttpServer server;

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void mapsTrainingWeatherCategories() {
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(0)).isEqualTo("맑음");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(2)).isEqualTo("구름많음");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(3)).isEqualTo("흐림");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(45)).isEqualTo("안개");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(61)).isEqualTo("비");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(75)).isEqualTo("눈");
		assertThat(OpenMeteoWeatherProvider.wmoToLabel(96)).isEqualTo("뇌우");
	}

	@Test
	void fetchesAndCachesCurrentWeather() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		server = server(exchange -> {
			requests.incrementAndGet();
			respond(exchange, 200, "{\"current\":{\"weather_code\":61}}");
		});
		OpenMeteoWeatherProvider provider = provider(serverUrl(), Duration.ofMinutes(15));
		StopEntity stop = stop();

		assertThat(provider.currentWeather(stop)).isEqualTo("비");
		assertThat(provider.currentWeather(stop)).isEqualTo("비");
		assertThat(requests).hasValue(1);
	}

	@Test
	void usesConfiguredFallbackWhenUpstreamFails() throws Exception {
		server = server(exchange -> respond(exchange, 503, "{}"));
		OpenMeteoWeatherProvider provider = provider(serverUrl(), Duration.ZERO);

		assertThat(provider.currentWeather(stop())).isEqualTo("맑음");
	}

	private OpenMeteoWeatherProvider provider(String baseUrl, Duration cacheTtl) {
		AppProperties appProperties = new AppProperties();
		AppProperties.Prediction properties = appProperties.getPrediction();
		properties.setWeatherBaseUrl(baseUrl);
		properties.setWeatherRequestTimeout(Duration.ofSeconds(2));
		properties.setWeatherCacheTtl(cacheTtl);
		properties.setDefaultWeather("맑음");
		return new OpenMeteoWeatherProvider(
				appProperties,
				Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC)
		);
	}

	private HttpServer server(ExchangeHandler handler) throws IOException {
		HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		httpServer.createContext("/forecast", exchange -> handler.handle(exchange));
		httpServer.start();
		return httpServer;
	}

	private String serverUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/forecast";
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static StopEntity stop() {
		return new StopEntity(
				"121000019", "11100", "LOCAL-ORIGIN", "22019", "고속터미널", "서초구",
				new BigDecimal("37.506300"), new BigDecimal("127.005140")
		);
	}

	@FunctionalInterface
	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws IOException;
	}
}
