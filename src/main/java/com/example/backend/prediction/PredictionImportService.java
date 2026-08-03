package com.example.backend.prediction;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PredictionImportService {

	private static final Logger log = LoggerFactory.getLogger(PredictionImportService.class);
	private static final int BATCH_SIZE = 1_000;
	private static final List<String> HEADER = List.of(
			"route_id", "board_stop_id", "alight_stop_id", "weekday", "prediction_hour",
			"weather", "usertype_code", "standing_seconds", "risk_level", "model_confidence",
			"boarding_sample_count", "od_sample_count", "travel_seconds", "model_version"
	);
	private static final Set<String> WEEKDAYS = Set.of("월", "화", "수", "목", "금", "토", "일");
	private static final Set<String> WEATHER_LABELS = Set.of(
			"맑음", "구름많음", "흐림", "안개", "비", "눈", "뇌우"
	);
	private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
	private static final String UPSERT = """
			insert into prediction (
				route_id, board_stop_id, alight_stop_id, weekday, prediction_hour,
				weather, usertype_code, standing_seconds, risk_level, model_confidence,
				boarding_sample_count, od_sample_count, travel_seconds, model_version
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on duplicate key update
				standing_seconds = values(standing_seconds),
				risk_level = values(risk_level),
				model_confidence = values(model_confidence),
				boarding_sample_count = values(boarding_sample_count),
				od_sample_count = values(od_sample_count),
				travel_seconds = values(travel_seconds),
				model_version = values(model_version)
			""";

	private final JdbcTemplate jdbcTemplate;

	public PredictionImportService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(rollbackFor = Exception.class)
	public long importFile(Path file) throws IOException {
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		long imported = 0;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			validateHeader(reader.readLine());
			String line;
			long lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				batch.add(parseRow(line, lineNumber));
				imported++;
				if (batch.size() >= BATCH_SIZE) {
					flush(batch);
				}
				if (imported % 10_000 == 0) {
					log.info("Prediction import progress: rows={}", imported);
				}
			}
		}
		flush(batch);
		if (imported == 0) {
			throw new PredictionImportException("예측 CSV에 데이터 행이 없습니다.");
		}
		log.info("Prediction import completed: rows={}", imported);
		return imported;
	}

	private void validateHeader(String line) {
		if (line == null) {
			throw new PredictionImportException("예측 CSV가 비어 있습니다.");
		}
		String normalized = line.startsWith("\uFEFF") ? line.substring(1) : line;
		List<String> actual = List.of(normalized.split(",", -1));
		if (!HEADER.equals(actual)) {
			throw new PredictionImportException(
					"예측 CSV 헤더가 올바르지 않습니다. 필요한 순서: " + String.join(",", HEADER)
			);
		}
	}

	private Object[] parseRow(String line, long lineNumber) {
		String[] fields = line.split(",", -1);
		if (fields.length != HEADER.size()) {
			throw invalid(lineNumber, "필드 수는 " + HEADER.size() + "개여야 합니다.");
		}
		String routeId = identifier(fields[0], lineNumber, "route_id");
		String boardStopId = identifier(fields[1], lineNumber, "board_stop_id");
		String alightStopId = identifier(fields[2], lineNumber, "alight_stop_id");
		String weekday = required(fields[3], lineNumber, "weekday");
		if (!WEEKDAYS.contains(weekday)) {
			throw invalid(lineNumber, "weekday는 월~일 한글 한 글자여야 합니다.");
		}
		int hour = integer(fields[4], lineNumber, "prediction_hour", 0, 23);
		String weather = required(fields[5], lineNumber, "weather");
		if (!WEATHER_LABELS.contains(weather)) {
			throw invalid(lineNumber, "지원하지 않는 weather 값입니다: " + weather);
		}
		String userTypeCode = required(fields[6], lineNumber, "usertype_code");
		if (!userTypeCode.matches("0[1-8]")) {
			throw invalid(lineNumber, "usertype_code는 01~08이어야 합니다.");
		}
		Integer standingSeconds = nullableInteger(fields[7], lineNumber, "standing_seconds", 0, Integer.MAX_VALUE);
		String riskLevel = nullable(fields[8]);
		if (riskLevel != null && !RISK_LEVELS.contains(riskLevel)) {
			throw invalid(lineNumber, "risk_level은 LOW, MEDIUM, HIGH 중 하나여야 합니다.");
		}
		if ((standingSeconds == null) != (riskLevel == null)) {
			throw invalid(lineNumber, "standing_seconds와 risk_level은 함께 입력하거나 함께 비워야 합니다.");
		}
		BigDecimal modelConfidence = nullableDecimal(fields[9], lineNumber, "model_confidence");
		if (modelConfidence != null
				&& (modelConfidence.compareTo(BigDecimal.ZERO) < 0 || modelConfidence.compareTo(BigDecimal.ONE) > 0)) {
			throw invalid(lineNumber, "model_confidence는 0~1이어야 합니다.");
		}
		int boardingSampleCount = integer(
				fields[10], lineNumber, "boarding_sample_count", 0, Integer.MAX_VALUE
		);
		int odSampleCount = integer(fields[11], lineNumber, "od_sample_count", 0, Integer.MAX_VALUE);
		int travelSeconds = integer(fields[12], lineNumber, "travel_seconds", 1, Integer.MAX_VALUE);
		String modelVersion = nullable(fields[13]);
		if (modelVersion != null && modelVersion.length() > 50) {
			throw invalid(lineNumber, "model_version은 50자를 넘을 수 없습니다.");
		}
		return new Object[] {
				routeId, boardStopId, alightStopId, weekday, hour, weather, userTypeCode,
				standingSeconds, riskLevel, modelConfidence, boardingSampleCount, odSampleCount,
				travelSeconds, modelVersion
		};
	}

	private void flush(List<Object[]> batch) {
		if (batch.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate(UPSERT, batch);
		batch.clear();
	}

	private static String identifier(String value, long lineNumber, String field) {
		String normalized = required(value, lineNumber, field);
		if (!normalized.matches("\\d{9}")) {
			throw invalid(lineNumber, field + "는 숫자 9자리여야 합니다.");
		}
		return normalized;
	}

	private static String required(String value, long lineNumber, String field) {
		String normalized = nullable(value);
		if (normalized == null) {
			throw invalid(lineNumber, field + " 값이 필요합니다.");
		}
		return normalized;
	}

	private static String nullable(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static int integer(
			String value,
			long lineNumber,
			String field,
			int minimum,
			int maximum
	) {
		Integer parsed = nullableInteger(value, lineNumber, field, minimum, maximum);
		if (parsed == null) {
			throw invalid(lineNumber, field + " 값이 필요합니다.");
		}
		return parsed;
	}

	private static Integer nullableInteger(
			String value,
			long lineNumber,
			String field,
			int minimum,
			int maximum
	) {
		String normalized = nullable(value);
		if (normalized == null) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(normalized);
			if (parsed < minimum || parsed > maximum) {
				throw invalid(lineNumber, field + " 값의 범위가 올바르지 않습니다.");
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw invalid(lineNumber, field + "는 정수여야 합니다.");
		}
	}

	private static BigDecimal nullableDecimal(String value, long lineNumber, String field) {
		String normalized = nullable(value);
		if (normalized == null) {
			return null;
		}
		try {
			return new BigDecimal(normalized);
		} catch (NumberFormatException exception) {
			throw invalid(lineNumber, field + "는 숫자여야 합니다.");
		}
	}

	private static PredictionImportException invalid(long lineNumber, String message) {
		return new PredictionImportException(lineNumber + "번째 줄: " + message);
	}
}
