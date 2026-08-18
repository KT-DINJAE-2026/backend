package com.example.backend.headway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.example.backend.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 서울시 노선 기본정보의 요일 유형별 계획 배차간격을 메모리에 적재한다.
 *
 * <p>원본의 배차간격은 분 단위이며, 저장소의 파생 CSV에는 초 단위로 변환해 보관한다. 이 값은
 * 실시간 차량 간격이 아닌 노선 전체의 계획값이다. 학습 Parquet의 승차 태그 기반 근사값과 정의가
 * 다르므로 최종 모델에서는 동일한 정의로 재학습해야 한다.</p>
 */
@Component
public class ScheduledHeadwayProvider implements HeadwayProvider {

	private static final Logger log = LoggerFactory.getLogger(ScheduledHeadwayProvider.class);
	private static final String EXPECTED_HEADER =
			"route_number,weekday_headway_sec,saturday_headway_sec,holiday_headway_sec";

	private final Map<String, Schedule> schedules;

	@Autowired
	public ScheduledHeadwayProvider(AppProperties appProperties, ResourceLoader resourceLoader) {
		AppProperties.Headway properties = appProperties.getHeadway();
		if (!properties.isEnabled()) {
			this.schedules = Map.of();
			log.info("계획 배차간격 공급자가 비활성화되어 headway_sec를 결측으로 제공합니다.");
			return;
		}

		Resource resource = resourceLoader.getResource(properties.getScheduleResource());
		try (InputStream inputStream = resource.getInputStream()) {
			this.schedules = load(inputStream, properties.getScheduleResource());
		} catch (IOException exception) {
			throw new IllegalStateException(
					"계획 배차간격 파일을 읽을 수 없습니다: " + properties.getScheduleResource(),
					exception
			);
		}
		log.info(
				"계획 배차간격 {}개 노선을 적재했습니다. resource={}",
				schedules.size(),
				properties.getScheduleResource()
		);
	}

	ScheduledHeadwayProvider(InputStream inputStream) {
		this.schedules = load(inputStream, "test-input");
	}

	@Override
	public Long headwaySeconds(String routeNumber, LocalDate serviceDate, boolean publicHoliday) {
		Objects.requireNonNull(serviceDate, "serviceDate는 null일 수 없습니다.");
		if (routeNumber == null || routeNumber.isBlank()) {
			return null;
		}
		Schedule schedule = schedules.get(routeNumber.strip());
		return schedule == null ? null : schedule.forDate(serviceDate, publicHoliday);
	}

	private static Map<String, Schedule> load(InputStream inputStream, String description) {
		Map<String, Schedule> loaded = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (!EXPECTED_HEADER.equals(header)) {
				throw new IllegalStateException("계획 배차간격 CSV 헤더가 올바르지 않습니다: " + description);
			}

			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				String[] columns = line.split(",", -1);
				if (columns.length != 4 || columns[0].isBlank()) {
					throw invalidRow(description, lineNumber, "컬럼 수 또는 노선번호가 올바르지 않습니다.");
				}
				String routeNumber = columns[0].strip();
				Schedule schedule = new Schedule(
						parseSeconds(columns[1], description, lineNumber),
						parseSeconds(columns[2], description, lineNumber),
						parseSeconds(columns[3], description, lineNumber)
				);
				Schedule previous = loaded.putIfAbsent(routeNumber, schedule);
				if (previous != null && !previous.equals(schedule)) {
					throw invalidRow(description, lineNumber, "같은 노선번호에 서로 다른 값이 있습니다.");
				}
			}
		} catch (IOException exception) {
			throw new IllegalStateException("계획 배차간격 CSV를 해석할 수 없습니다: " + description, exception);
		}
		return Map.copyOf(loaded);
	}

	private static Long parseSeconds(String rawValue, String description, int lineNumber) {
		String value = rawValue.strip();
		if (value.isEmpty() || "0".equals(value)) {
			return null;
		}
		try {
			long seconds = Long.parseLong(value);
			if (seconds < 0) {
				throw invalidRow(description, lineNumber, "배차간격은 음수일 수 없습니다.");
			}
			return seconds;
		} catch (NumberFormatException exception) {
			throw invalidRow(description, lineNumber, "배차간격이 정수가 아닙니다.", exception);
		}
	}

	private static IllegalStateException invalidRow(String description, int lineNumber, String message) {
		return new IllegalStateException(description + " " + lineNumber + "행: " + message);
	}

	private static IllegalStateException invalidRow(
			String description,
			int lineNumber,
			String message,
			Exception cause
	) {
		return new IllegalStateException(description + " " + lineNumber + "행: " + message, cause);
	}

	private record Schedule(Long weekdaySeconds, Long saturdaySeconds, Long holidaySeconds) {

		private Long forDate(LocalDate serviceDate, boolean publicHoliday) {
			if (publicHoliday || serviceDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
				return holidaySeconds;
			}
			if (serviceDate.getDayOfWeek() == DayOfWeek.SATURDAY) {
				return saturdaySeconds;
			}
			return weekdaySeconds;
		}
	}
}
