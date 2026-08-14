package com.example.backend.weather;

import java.util.Arrays;

/** Open-Meteo WMO 코드를 현재 PMML이 학습한 다섯 날씨 문자열로 축약한다. */
public enum ModelWeatherCategory {

	CLEAR("맑음"),
	MOSTLY_CLOUDY("구름많음"),
	OVERCAST("흐림"),
	RAIN("비"),
	SNOW("눈");

	private final String label;

	ModelWeatherCategory(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static boolean supports(String label) {
		return Arrays.stream(values()).anyMatch(category -> category.label.equals(label));
	}

	public static ModelWeatherCategory fromWmoCode(int code) {
		if (code >= 0 && code <= 1) {
			return CLEAR;
		}
		if (code == 2) {
			return MOSTLY_CLOUDY;
		}
		if (code == 3 || (code >= 45 && code <= 48)) {
			// 현재 모델에 안개 범주가 없어 가장 가까운 흐림으로 합친다.
			return OVERCAST;
		}
		if ((code >= 51 && code <= 67)
				|| (code >= 80 && code <= 82)
				|| (code >= 95 && code <= 99)) {
			// 현재 모델에 뇌우 범주가 없어 강수 상태인 비로 합친다.
			return RAIN;
		}
		if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) {
			return SNOW;
		}
		throw new IllegalArgumentException("지원하지 않는 WMO weather code입니다: " + code);
	}
}
