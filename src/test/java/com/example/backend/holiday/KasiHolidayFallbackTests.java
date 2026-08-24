package com.example.backend.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.backend.config.AppProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * KASI API 실패 시 내장 공휴일 목록 폴백을 검증한다.
 *
 * <p>연결 자체가 실패하도록 라우팅 불가 주소를 사용한다. KASI가 일부 클라우드 대역을 차단해
 * Cloud Run에서 연결 타임아웃이 났던 상황(2026-08-24)의 재현이다.</p>
 */
class KasiHolidayFallbackTests {

	private AppProperties appProperties;
	private AtomicReference<Instant> now;
	private KasiHolidayClient client;

	@BeforeEach
	void setUp() {
		appProperties = new AppProperties();
		// TEST-NET-1(RFC 5737) 주소라 연결이 즉시 실패한다.
		appProperties.getHoliday().setBaseUrl("http://192.0.2.1");
		appProperties.getHoliday().setServiceKey("test-key");
		appProperties.getHoliday().setConnectTimeout(Duration.ofMillis(200));
		appProperties.getHoliday().setFailureCacheTtl(Duration.ofMinutes(10));

		now = new AtomicReference<>(Instant.parse("2026-08-24T00:00:00Z"));
		Clock clock = new Clock() {
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
				return now.get();
			}
		};
		client = new KasiHolidayClient(appProperties, clock, new DefaultResourceLoader());
	}

	@Test
	void API_실패_시_내장_목록으로_공휴일을_판정한다() {
		// 내장 목록(holiday/kasi-holidays.csv)의 2026년 광복절과 평일을 대조한다.
		assertThat(client.isHoliday(LocalDate.of(2026, 8, 15))).isTrue();
		assertThat(client.isHoliday(LocalDate.of(2026, 8, 24))).isFalse();
	}

	@Test
	void 내장_목록에_없는_연도는_원래_예외를_던진다() {
		assertThatThrownBy(() -> client.isHoliday(LocalDate.of(2030, 1, 1)))
				.isInstanceOf(HolidayApiException.class);
	}

	@Test
	void 폴백_결과는_짧은_TTL로_캐시되어_매_요청이_타임아웃을_기다리지_않는다() {
		long before = System.nanoTime();
		client.isHoliday(LocalDate.of(2026, 8, 15));
		long firstMillis = (System.nanoTime() - before) / 1_000_000;

		before = System.nanoTime();
		client.isHoliday(LocalDate.of(2026, 8, 16));
		long cachedMillis = (System.nanoTime() - before) / 1_000_000;

		// 첫 호출만 연결 실패를 기다리고, TTL 안의 두 번째 호출은 캐시를 쓴다.
		assertThat(cachedMillis).isLessThan(firstMillis);

		// TTL이 지나면 API를 다시 시도한다(여전히 실패 → 다시 폴백, 예외 없음).
		now.set(now.get().plus(11, ChronoUnit.MINUTES));
		assertThat(client.isHoliday(LocalDate.of(2026, 8, 15))).isTrue();
	}

	@Test
	void 폴백_자료가_지정되지_않으면_기존처럼_예외를_던진다() {
		appProperties.getHoliday().setFallbackResource("");
		assertThatThrownBy(() -> client.isHoliday(LocalDate.of(2026, 8, 15)))
				.isInstanceOf(HolidayApiException.class);
	}
}
