package com.example.backend.headway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ScheduledHeadwayProviderTests {

	private static final String HEADER =
			"route_number,weekday_headway_sec,saturday_headway_sec,holiday_headway_sec\n";

	@Test
	void selectsTheScheduleForWeekdaySaturdaySundayAndPublicHoliday() {
		ScheduledHeadwayProvider provider = provider("1014,480,600,720\n");

		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 17), false))
				.isEqualTo(480L);
		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 22), false))
				.isEqualTo(600L);
		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 23), false))
				.isEqualTo(720L);
		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 17), true))
				.isEqualTo(720L);
	}

	@Test
	void returnsNullForZeroBlankAndUnmappedValues() {
		ScheduledHeadwayProvider provider = provider(
				"1014,0,,600\n"
						+ "103,540,600,600\n"
		);

		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 17), false)).isNull();
		assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 22), false)).isNull();
		assertThat(provider.headwaySeconds("9999", LocalDate.of(2026, 8, 17), false)).isNull();
		assertThat(provider.headwaySeconds(null, LocalDate.of(2026, 8, 17), false)).isNull();
	}

	@Test
	void loadsKnownValuesFromTheBundledSchedule() throws IOException {
		try (InputStream inputStream = getClass().getResourceAsStream(
				"/headway/seoul-bus-headway-20260804.csv"
		)) {
			assertThat(inputStream).isNotNull();
			ScheduledHeadwayProvider provider = new ScheduledHeadwayProvider(inputStream);

			assertThat(provider.headwaySeconds("1014", LocalDate.of(2026, 8, 17), false))
					.isEqualTo(480L);
			assertThat(provider.headwaySeconds("103", LocalDate.of(2026, 8, 22), false))
					.isEqualTo(600L);
			assertThat(provider.headwaySeconds("142", LocalDate.of(2026, 8, 23), false))
					.isEqualTo(600L);
		}
	}

	@Test
	void rejectsNegativeAndConflictingDuplicateSchedules() {
		assertThatThrownBy(() -> provider("1014,-1,600,600\n"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("음수");

		assertThatThrownBy(() -> provider("1014,480,600,600\n1014,540,600,600\n"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("서로 다른 값");
	}

	private static ScheduledHeadwayProvider provider(String rows) {
		return new ScheduledHeadwayProvider(new ByteArrayInputStream(
				(HEADER + rows).getBytes(StandardCharsets.UTF_8)
		));
	}
}
