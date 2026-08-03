package com.example.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.example.backend.arrival.ArrivalClient;
import com.example.backend.arrival.ArrivalLookupResult;
import com.example.backend.arrival.ArrivalLookupStatus;
import com.example.backend.arrival.BusArrival;
import com.example.backend.arrival.TopisApiException;
import com.example.backend.domain.PredictionEntity;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.RouteStopEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.error.ApiException;
import com.example.backend.error.ErrorCode;
import com.example.backend.prediction.dto.JourneyPredictionRequest;
import com.example.backend.prediction.dto.JourneyPredictionResponse;
import com.example.backend.repository.PredictionRepository;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
		"app.topis.cache-ttl=0s",
		"app.prediction.default-weather=맑음",
		"app.prediction.weather-enabled=false",
		"app.prediction.sample-basis=BOARDING_STOP"
})
@Transactional
class JourneyPredictionServiceIntegrationTests {

	@Autowired
	private JourneyPredictionService predictionService;

	@Autowired
	private StopRepository stopRepository;

	@Autowired
	private RouteRepository routeRepository;

	@Autowired
	private RouteStopRepository routeStopRepository;

	@Autowired
	private PredictionRepository predictionRepository;

	@Autowired
	private FakeArrivalClient arrivalClient;

	@Autowired
	private FakeWeatherProvider weatherProvider;

	private StopEntity origin;
	private StopEntity destination;
	private RouteEntity route;

	@BeforeEach
	void setUp() {
		arrivalClient.fail = false;
		weatherProvider.weather = "맑음";
		origin = saveStop("121000019", "22019", "고속터미널");
		StopEntity middle = saveStop("121000020", "22020", "고속터미널중앙");
		destination = saveStop("121000021", "22021", "신반포역.세화여중고");
		route = routeRepository.save(new RouteEntity(
				"100100027", "11100", "LOCAL-ROUTE", "148", "B", "방배동", "번동", "115"
		));
		routeStopRepository.save(new RouteStopEntity(route, origin, 10, 300));
		routeStopRepository.save(new RouteStopEntity(route, middle, 11, 700));
		routeStopRepository.save(new RouteStopEntity(route, destination, 12, 200));
		savePrediction(150, "MEDIUM", 150, 50, 600);
	}

	@Test
	void successResponsePreservesSegmentAndStandingMinuteInvariants() {
		JourneyPredictionResponse response = predictionService.predict(request(origin, destination));

		assertThat(response.status()).isEqualTo(JourneyStatus.SUCCESS);
		assertThat(response.reasonCode()).isNull();
		assertThat(response.predictionBasis().confidence()).isEqualTo(PredictionConfidence.MEDIUM);
		assertThat(response.routes()).hasSize(2);
		assertThat(response.routes().getFirst()).satisfies(result -> {
			assertThat(result.routeId()).isEqualTo(route.getId());
			assertThat(result.routeNumber()).isEqualTo("148");
			assertThat(result.travelMinutes()).isEqualTo(10);
			assertThat(result.standingBurdenMinutes()).isEqualTo(3);
			assertThat(result.standingBurdenLevel()).isEqualTo(StandingBurdenLevel.MEDIUM);
			assertThat(result.segments()).hasSize(2);
			assertThat(result.segments()).extracting(segment -> segment.durationMinutes())
					.containsExactly(3, 7);
			assertThat(result.segments()).extracting(segment -> segment.congestionLevel())
					.containsExactly(CongestionLevel.CROWDED, CongestionLevel.RELAXED);
			assertThat(result.segments().getFirst().fromStopId()).isEqualTo(origin.getId());
			assertThat(result.segments().getLast().toStopId()).isEqualTo(destination.getId());
		});
		assertThat(response.routes()).extracting(result -> result.tripId()).doesNotHaveDuplicates();
	}

	@Test
	void insufficientResponseOmitsStandingPrediction() {
		predictionRepository.deleteAllInBatch();
		savePrediction(null, null, 1, 1, 600);

		JourneyPredictionResponse response = predictionService.predict(request(origin, destination));

		assertThat(response.status()).isEqualTo(JourneyStatus.INSUFFICIENT_DATA);
		assertThat(response.reasonCode()).isEqualTo("NOT_ENOUGH_HISTORICAL_SAMPLES");
		assertThat(response.predictionBasis().confidence()).isEqualTo(PredictionConfidence.UNAVAILABLE);
		assertThat(response.routes()).allSatisfy(result -> {
			assertThat(result.travelMinutes()).isEqualTo(10);
			assertThat(result.standingBurdenMinutes()).isNull();
			assertThat(result.standingBurdenLevel()).isNull();
			assertThat(result.segments()).isNull();
		});
	}

	@Test
	void arrivalAuthenticationFailureDoesNotBecomePredictionFailure() {
		arrivalClient.fail = true;

		JourneyPredictionResponse response = predictionService.predict(request(origin, destination));

		assertThat(response.status()).isEqualTo(JourneyStatus.SUCCESS);
		assertThat(response.routes()).isEmpty();
	}

	@Test
	void selectsCurrentWeatherPredictionBeforeDefaultFallback() {
		weatherProvider.weather = "비";
		predictionRepository.save(new PredictionEntity(
				route, origin, destination, "월", 9, "비", "04",
				360, "HIGH", new BigDecimal("0.9300"),
				1200, 300, 660, "rain-model"
		));

		JourneyPredictionResponse response = predictionService.predict(request(origin, destination));

		assertThat(response.predictionBasis().confidence()).isEqualTo(PredictionConfidence.HIGH);
		assertThat(response.routes()).allSatisfy(result -> {
			assertThat(result.travelMinutes()).isEqualTo(11);
			assertThat(result.standingBurdenLevel()).isEqualTo(StandingBurdenLevel.HIGH);
		});
	}

	@Test
	void fallsBackToDefaultWeatherPredictionWhenCurrentCombinationIsMissing() {
		weatherProvider.weather = "눈";

		JourneyPredictionResponse response = predictionService.predict(request(origin, destination));

		assertThat(response.status()).isEqualTo(JourneyStatus.SUCCESS);
		assertThat(response.routes()).allSatisfy(result -> assertThat(result.travelMinutes()).isEqualTo(10));
	}

	@Test
	void reverseDirectionReturnsDirectionMismatch() {
		assertThatThrownBy(() -> predictionService.predict(request(destination, origin)))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.STOP_DIRECTION_MISMATCH));
	}

	@Test
	void unrelatedStopsReturnNoDirectRoute() {
		StopEntity unrelated = saveStop("121009999", "22999", "양재역");

		assertThatThrownBy(() -> predictionService.predict(request(origin, unrelated)))
				.isInstanceOf(ApiException.class)
				.satisfies(exception -> assertThat(((ApiException) exception).errorCode())
						.isEqualTo(ErrorCode.NO_DIRECT_ROUTE));
	}

	private void savePrediction(
			Integer standingSeconds,
			String riskLevel,
			int boardingSampleCount,
			int odSampleCount,
			int travelSeconds
	) {
		predictionRepository.save(new PredictionEntity(
				route, origin, destination, "월", 9, "맑음", "04",
				standingSeconds, riskLevel, new BigDecimal("0.8120"),
				boardingSampleCount, odSampleCount, travelSeconds, "test-model"
		));
	}

	private StopEntity saveStop(String id, String arsId, String name) {
		return stopRepository.save(new StopEntity(
				id, "11100", "LOCAL-" + id, arsId, name, "서초구",
				new BigDecimal("37.506300"), new BigDecimal("127.005140")
		));
	}

	private static JourneyPredictionRequest request(StopEntity origin, StopEntity destination) {
		return new JourneyPredictionRequest(origin.getId(), destination.getId(), null);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(
					Instant.parse("2026-08-03T00:00:00Z"),
					ZoneId.of("Asia/Seoul")
			);
		}

		@Bean
		@Primary
		FakeArrivalClient fakeArrivalClient() {
			return new FakeArrivalClient();
		}

		@Bean
		@Primary
		FakeWeatherProvider fakeWeatherProvider() {
			return new FakeWeatherProvider();
		}
	}

	static class FakeArrivalClient implements ArrivalClient {

		private boolean fail;

		@Override
		public ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder) {
			if (fail) {
				throw new TopisApiException(
						TopisApiException.Reason.AUTHENTICATION,
						"test authentication failure"
				);
			}
			return new ArrivalLookupResult(
					ArrivalLookupStatus.AVAILABLE,
					OffsetDateTime.parse("2026-08-03T09:00:00+09:00"),
					List.of(
							arrival(routeId, "vehicle-1", 2, false),
							arrival(routeId, "vehicle-2", 5, true)
					)
			);
		}

		private static BusArrival arrival(String routeId, String tripId, int arrivalMinutes, boolean lowFloor) {
			return new BusArrival(
					tripId, routeId, "148", "번동 방면", null,
					lowFloor ? "저상버스" : "일반버스", lowFloor,
					arrivalMinutes * 60, arrivalMinutes, "", false, false, false
			);
		}
	}

	static class FakeWeatherProvider implements WeatherProvider {

		private String weather = "맑음";

		@Override
		public String currentWeather(StopEntity stop) {
			return weather;
		}
	}
}
