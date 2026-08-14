package com.example.backend.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** WMO 코드가 현재 PMML의 다섯 문자열 외 값으로 새지 않는지 검증한다. */
class ModelWeatherCategoryTests {

	@Test
	void mapsWmoCodesToTheFiveModelCategories() {
		assertThat(ModelWeatherCategory.fromWmoCode(0).label()).isEqualTo("맑음");
		assertThat(ModelWeatherCategory.fromWmoCode(2).label()).isEqualTo("구름많음");
		assertThat(ModelWeatherCategory.fromWmoCode(3).label()).isEqualTo("흐림");
		assertThat(ModelWeatherCategory.fromWmoCode(45).label()).isEqualTo("흐림");
		assertThat(ModelWeatherCategory.fromWmoCode(61).label()).isEqualTo("비");
		assertThat(ModelWeatherCategory.fromWmoCode(95).label()).isEqualTo("비");
		assertThat(ModelWeatherCategory.fromWmoCode(75).label()).isEqualTo("눈");
	}

	@Test
	void rejectsUnknownWmoCode() {
		assertThatThrownBy(() -> ModelWeatherCategory.fromWmoCode(100))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
