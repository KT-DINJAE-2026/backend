package com.example.backend.arrival;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.backend.config.AppProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 노선 전체 도착예정 응답을 짧게 캐시해 후보 차량과 구간 ETA 계산이 같은 스냅샷을 사용하게 한다. */
@Service
@ConditionalOnBean(RouteArrivalClient.class)
public class TopisRouteArrivalService implements RouteArrivalProvider {

	private final RouteArrivalClient client;
	private final AppProperties.Topis properties;
	private final Clock clock;
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	public TopisRouteArrivalService(
			RouteArrivalClient client,
			AppProperties appProperties,
			Clock clock
	) {
		this.client = client;
		this.properties = appProperties.getTopis();
		this.clock = clock;
	}

	@Override
	public RouteArrivalSnapshot getRouteArrivals(String routeId) {
		Duration cacheTtl = properties.getCacheTtl();
		if (cacheTtl.isZero() || cacheTtl.isNegative()) {
			return client.getRouteArrivals(routeId);
		}

		Instant now = clock.instant();
		cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		return cache.compute(routeId, (ignored, current) -> {
			if (current != null && current.expiresAt().isAfter(now)) {
				return current;
			}
			return new CacheEntry(client.getRouteArrivals(routeId), now.plus(cacheTtl));
		}).snapshot();
	}

	int cachedEntryCount() {
		return cache.size();
	}

	private record CacheEntry(RouteArrivalSnapshot snapshot, Instant expiresAt) {
	}
}
