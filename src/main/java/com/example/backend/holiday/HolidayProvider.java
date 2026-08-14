package com.example.backend.holiday;

import java.time.LocalDate;

/** 모델의 {@code is_holiday} 피처에 사용할 대한민국 법정 공휴일 여부를 제공한다. */
public interface HolidayProvider {

	/** 주말 여부가 아니라 학습 데이터와 동일한 대한민국 법정 공휴일 여부만 반환한다. */
	boolean isHoliday(LocalDate date);
}
