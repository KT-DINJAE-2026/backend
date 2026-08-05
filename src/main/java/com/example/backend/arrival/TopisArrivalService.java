package com.example.backend.arrival;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.backend.config.AppProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 동일한 TOPIS 조회를 짧은 시간 동안 재사용하는 캐시 계층이다.
 *
 * <p>도착정보는 자주 바뀌지만 검색 화면의 반복 요청마다 외부 API를 호출하지 않도록
 * 기본 20초 TTL을 적용한다. 현재 여정 테스트 서비스에는 아직 연결되지 않았다.</p>
 */
@Service
@ConditionalOnBean(ArrivalClient.class)
public class TopisArrivalService {

	private final ArrivalClient arrivalClient;
	private final AppProperties.Topis properties;
	private final Clock clock;
	private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

	public TopisArrivalService(ArrivalClient arrivalClient, AppProperties appProperties, Clock clock) {
		this.arrivalClient = arrivalClient;
		this.properties = appProperties.getTopis();
		this.clock = clock;
	}

	public ArrivalLookupResult getArrivals(String stopId, String routeId, int stopOrder) {
		Duration cacheTtl = properties.getCacheTtl();
		if (cacheTtl.isZero() || cacheTtl.isNegative()) {
			return arrivalClient.getArrivals(stopId, routeId, stopOrder);
		}

		CacheKey key = new CacheKey(stopId, routeId, stopOrder);
		Instant now = clock.instant();
		// 새 키를 조회할 때마다 만료 항목을 함께 제거해 조회 조합이 늘어도 메모리가 누적되지 않게 한다.
		cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		// compute를 사용해 동일 키의 동시 요청도 외부 호출 한 번으로 합친다.
		CacheEntry entry = cache.compute(key, (ignored, current) -> {
			if (current != null && current.expiresAt().isAfter(now)) {
				return current;
			}
			ArrivalLookupResult result = arrivalClient.getArrivals(stopId, routeId, stopOrder);
			return new CacheEntry(result, now.plus(cacheTtl));
		});
		return entry.result();
	}

	/** 캐시 정리 동작을 외부 구현 세부사항 노출 없이 같은 패키지의 테스트에서 검증한다. */
	int cachedEntryCount() {
		return cache.size();
	}

	private record CacheKey(String stopId, String routeId, int stopOrder) {
	}

	private record CacheEntry(ArrivalLookupResult result, Instant expiresAt) {
	}
}
