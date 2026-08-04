package com.example.backend.arrival;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 외부 도착정보 조회 결과를 상태와 함께 표현한다.
 *
 * <p>정상 HTTP 응답이어도 운행 종료나 도착 예정 차량 없음일 수 있으므로
 * 빈 배열만으로 의미를 추측하지 않고 {@link ArrivalLookupStatus}를 함께 전달한다.</p>
 */
public record ArrivalLookupResult(
		ArrivalLookupStatus status,
		OffsetDateTime providedAt,
		List<BusArrival> arrivals
) {

	public ArrivalLookupResult {
		// 캐시된 결과가 호출자에 의해 변경되지 않도록 방어적 복사한다.
		arrivals = List.copyOf(arrivals);
	}

	public static ArrivalLookupResult empty(ArrivalLookupStatus status, OffsetDateTime providedAt) {
		return new ArrivalLookupResult(status, providedAt, List.of());
	}
}
