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

class TopisArrivalServiceTests {

	@Test
	void cachesTheSameStopAndRouteForTwentySeconds() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-03T03:00:00Z"));
		AtomicInteger calls = new AtomicInteger();
		ArrivalClient client = (stopId, routeId, stopOrder) -> {
			calls.incrementAndGet();
			return ArrivalLookupResult.empty(
					ArrivalLookupStatus.NO_ARRIVAL,
					OffsetDateTime.now(clock)
			);
		};
		AppProperties properties = new AppProperties();
		properties.getTopis().setCacheTtl(Duration.ofSeconds(20));
		TopisArrivalService service = new TopisArrivalService(client, properties, clock);

		service.getArrivals("121000019", "100100027", 35);
		service.getArrivals("121000019", "100100027", 35);
		assertThat(calls).hasValue(1);

		clock.advance(Duration.ofSeconds(21));
		service.getArrivals("121000019", "100100027", 35);
		assertThat(calls).hasValue(2);
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
