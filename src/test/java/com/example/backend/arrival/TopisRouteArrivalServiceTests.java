package com.example.backend.arrival;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.backend.config.AppProperties;

import org.junit.jupiter.api.Test;

class TopisRouteArrivalServiceTests {

	@Test
	void cachesOneRouteSnapshotAndRemovesExpiredEntries() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T05:30:00Z"));
		AtomicInteger calls = new AtomicInteger();
		RouteArrivalClient client = routeId -> {
			calls.incrementAndGet();
			return RouteArrivalSnapshot.empty(
					ArrivalLookupStatus.NO_ARRIVAL,
					OffsetDateTime.now(clock),
					routeId
			);
		};
		AppProperties properties = new AppProperties();
		properties.getTopis().setCacheTtl(Duration.ofSeconds(20));
		TopisRouteArrivalService service = new TopisRouteArrivalService(client, properties, clock);

		service.getRouteArrivals("100100129");
		service.getRouteArrivals("100100129");
		assertThat(calls).hasValue(1);
		assertThat(service.cachedEntryCount()).isEqualTo(1);

		clock.advance(Duration.ofSeconds(21));
		service.getRouteArrivals("100100031");
		assertThat(calls).hasValue(2);
		assertThat(service.cachedEntryCount()).isEqualTo(1);
	}

	@Test
	void bypassesTheCacheWhenTtlIsDisabled() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T05:30:00Z"));
		AtomicInteger calls = new AtomicInteger();
		RouteArrivalClient client = routeId -> {
			calls.incrementAndGet();
			return RouteArrivalSnapshot.empty(
					ArrivalLookupStatus.NO_ARRIVAL,
					OffsetDateTime.now(clock),
					routeId
			);
		};
		AppProperties properties = new AppProperties();
		properties.getTopis().setCacheTtl(Duration.ZERO);
		TopisRouteArrivalService service = new TopisRouteArrivalService(client, properties, clock);

		service.getRouteArrivals("100100129");
		service.getRouteArrivals("100100129");

		assertThat(calls).hasValue(2);
		assertThat(service.cachedEntryCount()).isZero();
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Seoul");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
