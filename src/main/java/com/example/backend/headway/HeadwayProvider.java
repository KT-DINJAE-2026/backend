package com.example.backend.headway;

import java.time.LocalDate;

/** 노선과 운행일 유형에 맞는 계획 배차간격을 초 단위로 제공한다. */
@FunctionalInterface
public interface HeadwayProvider {

	/**
	 * @param routeNumber 사용자에게 표시하는 서울시 노선번호
	 * @param serviceDate 차량의 서울 기준 예상 승차일
	 * @param publicHoliday 한국천문연구원 특일 정보 기준 공휴일 여부
	 * @return 계획 배차간격(초). 0·결측·미매핑 노선이면 {@code null}
	 */
	Long headwaySeconds(String routeNumber, LocalDate serviceDate, boolean publicHoliday);
}
