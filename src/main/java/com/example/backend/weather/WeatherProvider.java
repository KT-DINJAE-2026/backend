package com.example.backend.weather;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 승차 정류장 위치와 승차 예정 시각에 해당하는 모델용 날씨 범주를 제공한다. */
public interface WeatherProvider {

	/** PMML이 학습한 다섯 문자열 중 하나를 반환하며 원본 WMO 코드는 외부로 노출하지 않는다. */
	String weatherAt(BigDecimal latitude, BigDecimal longitude, OffsetDateTime boardingTime);
}
