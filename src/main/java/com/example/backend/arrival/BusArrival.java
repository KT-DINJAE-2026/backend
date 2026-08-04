package com.example.backend.arrival;

/**
 * TOPIS의 첫 번째·두 번째 도착예정 정보를 애플리케이션 공통 형식으로 정규화한 값이다.
 *
 * <p>{@code tripId}는 차량 ID를 우선 사용하며, 차량 ID가 없으면 노선 ID와 차량번호로 만든다.
 * 이 값은 같은 노선의 연속 도착 차량을 FE에서 구분하는 키이다.</p>
 */
public record BusArrival(
		String tripId,
		String routeId,
		String routeNumber,
		String direction,
		String vehicleNumber,
		String vehicleType,
		boolean lowFloor,
		int arrivalSeconds,
		int arrivalMinutes,
		String arrivalMessage,
		boolean lastBus,
		boolean full,
		boolean detour
) {
}
