package com.example.backend.arrival;

/** 도착정보 제공자의 결과 상태. 데이터 부족이나 장애를 빈 차량 목록과 구분할 때 사용한다. */
public enum ArrivalLookupStatus {
	/** 한 대 이상의 도착 예정 차량이 있음. */
	AVAILABLE,
	/** 조회는 성공했지만 현재 도착 예정 차량이 없음. */
	NO_ARRIVAL,
	/** 해당 노선의 당일 운행이 종료됨. */
	SERVICE_ENDED,
	/** 상위 서비스의 일시 장애나 점검 상태. */
	TEMPORARILY_UNAVAILABLE,
	/** 설정으로 외부 도착정보 조회를 비활성화함. */
	DISABLED
}
