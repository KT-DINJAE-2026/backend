package com.example.backend.prediction.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.holiday.HolidayProvider;

import org.junit.jupiter.api.Test;

/** 승차 예정 시각이 학습 피처의 서울 시간·요일·공휴일 값으로 변환되는지 검증한다. */
class PredictionModelInputFactoryTests {

	@Test
	void createsEightRawFeaturesFromTheSeoulBoardingTime() {
		AtomicReference<LocalDate> requestedDate = new AtomicReference<>();
		HolidayProvider holidayProvider = date -> {
			requestedDate.set(date);
			return true;
		};
		PredictionModelInputFactory factory = new PredictionModelInputFactory(
				holidayProvider,
				(latitude, longitude, time) -> "맑음"
		);

		PredictionModelInput input = factory.create(
				"100100129",
				"107000087",
				"107000089",
				OffsetDateTime.parse("2026-08-14T16:30:00Z"),
				"맑음",
				420L
		);

		assertThat(requestedDate).hasValue(LocalDate.of(2026, 8, 15));
		assertThat(input.weekday()).isEqualTo("토");
		assertThat(input.hour()).isEqualTo(1);
		assertThat(input.holiday()).isTrue();
		assertThat(new ArrayList<>(input.asRawFeatureMap().keySet()))
				.containsExactlyElementsOf(PredictionModelInput.FEATURE_NAMES);
		assertThat(input.asRawFeatureMap())
				.containsEntry("route_id", "100100129")
				.containsEntry("weather", "맑음")
				.containsEntry("headway_sec", 420L);
	}

	@Test
	void keepsMissingWeatherAndHeadwayForTheModelAdapter() {
		PredictionModelInputFactory factory = new PredictionModelInputFactory(
				date -> false,
				(latitude, longitude, time) -> "맑음"
		);

		PredictionModelInput input = factory.create(
				"100100129",
				"107000087",
				"107000089",
				OffsetDateTime.parse("2026-08-17T09:00:00+09:00"),
				null,
				null
		);

		assertThat(input.weekday()).isEqualTo("월");
		assertThat(input.holiday()).isFalse();
		assertThat(input.asRawFeatureMap().get("weather")).isNull();
		assertThat(input.asRawFeatureMap().get("headway_sec")).isNull();
	}

	@Test
	void looksUpWeatherFromTheBoardingStopCoordinates() {
		PredictionModelInputFactory factory = new PredictionModelInputFactory(
				date -> false,
				(latitude, longitude, time) -> {
					assertThat(latitude).isEqualByComparingTo("37.59000000");
					assertThat(longitude).isEqualByComparingTo("127.02000000");
					assertThat(time).isEqualTo(OffsetDateTime.parse("2026-08-17T09:00:00+09:00"));
					return "구름많음";
				}
		);

		PredictionModelInput input = factory.createForStop(
				"100100129",
				"107000087",
				"107000089",
				new BigDecimal("37.59000000"),
				new BigDecimal("127.02000000"),
				OffsetDateTime.parse("2026-08-17T09:00:00+09:00"),
				420L
		);

		assertThat(input.weather()).isEqualTo("구름많음");
	}

	@Test
	void rejectsNegativeHeadway() {
		assertThatThrownBy(() -> new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "맑음", 9, false, -1L
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsWeatherCategoryThatTheModelDidNotLearn() {
		assertThatThrownBy(() -> new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "안개", 9, false, 420L
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesTheExpectedFeatureContract() {
		assertThat(PredictionModelInput.FEATURE_NAMES).isEqualTo(List.of(
				"route_id", "board_stop_id", "alight_stop_id", "weekday",
				"weather", "hour", "is_holiday", "headway_sec"
		));
	}
}
