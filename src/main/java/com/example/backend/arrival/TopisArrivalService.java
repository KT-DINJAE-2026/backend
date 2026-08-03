package com.example.backend.arrival;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.backend.config.AppProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

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
		CacheEntry entry = cache.compute(key, (ignored, current) -> {
			if (current != null && current.expiresAt().isAfter(now)) {
				return current;
			}
			ArrivalLookupResult result = arrivalClient.getArrivals(stopId, routeId, stopOrder);
			return new CacheEntry(result, now.plus(cacheTtl));
		});
		return entry.result();
	}

	private record CacheKey(String stopId, String routeId, int stopOrder) {
	}

	private record CacheEntry(ArrivalLookupResult result, Instant expiresAt) {
	}
}
