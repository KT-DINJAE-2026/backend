package com.example.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.backend.domain.PredictionEntity;
import com.example.backend.domain.RouteEntity;
import com.example.backend.domain.StopEntity;
import com.example.backend.repository.PredictionRepository;
import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.StopRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PredictionImportServiceTests {

	private static final String HEADER = "route_id,board_stop_id,alight_stop_id,weekday,prediction_hour,"
			+ "weather,usertype_code,standing_seconds,risk_level,model_confidence,"
			+ "boarding_sample_count,od_sample_count,travel_seconds,model_version";

	@Autowired
	private PredictionImportService importService;

	@Autowired
	private PredictionRepository predictionRepository;

	@Autowired
	private StopRepository stopRepository;

	@Autowired
	private RouteRepository routeRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@TempDir
	private Path tempDirectory;

	@BeforeEach
	void setUp() {
		StopEntity origin = saveStop("121000019", "22019", "고속터미널");
		StopEntity destination = saveStop("121000021", "22021", "신반포역.세화여중고");
		routeRepository.save(new RouteEntity(
				"100100027", "11100", "LOCAL-ROUTE", "148", "B", "방배동", "번동", "115"
		));
		stopRepository.flush();
		routeRepository.flush();
		assertThat(origin).isNotNull();
		assertThat(destination).isNotNull();
	}

	@Test
	void importsAndUpsertsPredictionRows() throws Exception {
		Path first = write("first.csv", row("150", "MEDIUM", "0.8120", "150", "50", "600", "model-a"));

		assertThat(importService.importFile(first)).isEqualTo(1);
		PredictionEntity imported = findPrediction();
		assertThat(imported.getStandingSeconds()).isEqualTo(150);
		assertThat(imported.getBoardingSampleCount()).isEqualTo(150);
		assertThat(imported.getTravelSeconds()).isEqualTo(600);

		Path second = write("second.csv", row("360", "HIGH", "0.9300", "1200", "300", "660", "model-b"));
		assertThat(importService.importFile(second)).isEqualTo(1);
		entityManager.clear();

		assertThat(predictionRepository.count()).isEqualTo(1);
		PredictionEntity updated = findPrediction();
		assertThat(updated.getStandingSeconds()).isEqualTo(360);
		assertThat(updated.getRiskLevel()).isEqualTo("HIGH");
		assertThat(updated.getBoardingSampleCount()).isEqualTo(1200);
		assertThat(updated.getModelVersion()).isEqualTo("model-b");
	}

	@Test
	void rejectsInvalidRowsWithLineNumber() throws Exception {
		Path file = write("invalid.csv", row("-1", "MEDIUM", "0.8120", "150", "50", "600", "model-a"));

		assertThatThrownBy(() -> importService.importFile(file))
				.isInstanceOf(PredictionImportException.class)
				.hasMessageContaining("2번째 줄")
				.hasMessageContaining("standing_seconds");
		assertThat(predictionRepository.count()).isZero();
	}

	@Test
	void acceptsUtf8BomAndInsufficientDataFields() throws Exception {
		Path file = tempDirectory.resolve("bom.csv");
		Files.writeString(
				file,
				"\uFEFF" + HEADER + System.lineSeparator()
						+ row("", "", "", "1", "1", "600", "model-a")
						+ System.lineSeparator(),
				StandardCharsets.UTF_8
		);

		assertThat(importService.importFile(file)).isEqualTo(1);
		PredictionEntity imported = findPrediction();
		assertThat(imported.getStandingSeconds()).isNull();
		assertThat(imported.getRiskLevel()).isNull();
	}

	private PredictionEntity findPrediction() {
		return predictionRepository
				.findFirstByRoute_IdAndBoardingStop_IdAndAlightingStop_IdAndWeekdayAndHourAndWeatherAndUserTypeCode(
						"100100027", "121000019", "121000021", "월", 9, "맑음", "04"
				)
				.orElseThrow();
	}

	private Path write(String name, String row) throws Exception {
		Path file = tempDirectory.resolve(name);
		Files.writeString(
				file,
				HEADER + System.lineSeparator() + row + System.lineSeparator(),
				StandardCharsets.UTF_8
		);
		return file;
	}

	private static String row(
			String standingSeconds,
			String riskLevel,
			String confidence,
			String boardingSamples,
			String odSamples,
			String travelSeconds,
			String modelVersion
	) {
		return String.join(",",
				"100100027", "121000019", "121000021", "월", "9", "맑음", "04",
				standingSeconds, riskLevel, confidence, boardingSamples, odSamples, travelSeconds, modelVersion
		);
	}

	private StopEntity saveStop(String id, String arsId, String name) {
		return stopRepository.save(new StopEntity(
				id, "11100", "LOCAL-" + id, arsId, name, "서초구",
				new BigDecimal("37.506300"), new BigDecimal("127.005140")
		));
	}
}
