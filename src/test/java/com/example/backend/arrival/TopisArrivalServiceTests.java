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

/** 동일 조회 키의 캐시 재사용과 TTL 만료 후 재호출을 검증한다. */
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

	/** 실제 대기 없이 캐시 만료 시각을 전진시키기 위한 테스트 전용 Clock이다. */
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
