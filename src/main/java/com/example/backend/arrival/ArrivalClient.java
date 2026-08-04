package com.example.backend.arrival;

/**
 * 정류장·노선·경유 순번을 기준으로 도착 예정 차량을 조회하는 외부 연동 경계이다.
 *
 * <p>여정 서비스가 TOPIS HTTP/XML 구현에 직접 의존하지 않게 하며,
 * 테스트에서는 이 인터페이스를 가짜 구현으로 교체할 수 있다.</p>
 */
public interface ArrivalClient {

	/**
	 * 해당 노선이 지정 정류장에 도착하는 차량을 조회한다.
	 *
	 * @param stopId 서울시 정류장 표준 ID
	 * @param routeId 서울시 노선 표준 ID
	 * @param stopOrder 노선 안에서 정류장이 나타나는 순번
	 */
	ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder);
}
