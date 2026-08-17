package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.backend.config.AppProperties;
import com.example.backend.prediction.feature.PredictionModelInput;

import org.jpmml.evaluator.InputField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI팀이 전달한 실제 PMML이 우리가 기록한 입력 계약대로 적재되는지 확인한다.
 *
 * <p>모델 파일은 저장소에 없으므로(models/README.md) CI에서는 통째로 건너뛴다. 모델을 교체한
 * 사람은 로컬에서 이 테스트로 계약이 유지되었는지 먼저 확인해야 한다.</p>
 */
@EnabledIf("realModelsPresent")
class RealStandingModelSmokeTests {

	private static final Logger log = LoggerFactory.getLogger(RealStandingModelSmokeTests.class);

	private static final Path DIRECTORY = Path.of("models");

	static boolean realModelsPresent() {
		return Files.isRegularFile(DIRECTORY.resolve("model_a.pmml"))
				&& Files.isRegularFile(DIRECTORY.resolve("model_b.pmml"));
	}

	@Test
	void 실제_모델_두_개가_기록된_입력_계약대로_적재된다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setDirectory(DIRECTORY.toString());

		long baselineBytes = usedHeapBytes();
		long startedAt = System.nanoTime();
		StandingModels models = StandingModels.load(properties);
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
		long retainedBytes = usedHeapBytes() - baselineBytes;

		assertThat(models.isAvailable()).isTrue();
		assertThat(inputNames(models)).containsExactlyElementsOf(PredictionModelInput.FEATURE_NAMES);
		assertThat(models.classifier().getOutputFields().stream().map(field -> field.getName()).toList())
				.contains("probability(1)");

		// 콜드 스타트 예산과 Cloud Run 메모리 산정 근거로 남긴다. 모델 교체 시 함께 다시 본다.
		log.info(
				"실제 모델 적재: {}ms, 상주 힙 약 {}MiB",
				elapsedMillis,
				retainedBytes / (1024 * 1024)
		);
	}

	@Test
	void 모델_A와_B의_입력_피처가_서로_같다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setDirectory(DIRECTORY.toString());

		StandingModels models = StandingModels.load(properties);

		List<String> classifierInputs = models.classifier().getInputFields().stream()
				.map(InputField::getName)
				.toList();
		List<String> regressorInputs = models.regressor().getInputFields().stream()
				.map(InputField::getName)
				.toList();

		assertThat(classifierInputs).isEqualTo(regressorInputs);
	}

	@Test
	void 데모_시나리오_구간을_실제_모델로_예측한다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setDirectory(DIRECTORY.toString());
		StandingPredictor predictor = new PmmlStandingPredictor(StandingModels.load(properties));

		StandingPrediction prediction = predictor.predict(new PredictionModelInput(
				"100100129", "107000087", "107000089", "월", "맑음", 8, false, 420L
		));

		// 원본 ID를 정수로, 요일·날씨를 한글로 넣는 계약이 맞아야 여기까지 온다.
		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.PREDICTED);
		assertThat(prediction.standingProbability()).isBetween(0.0d, 1.0d);
		if (prediction.standing()) {
			assertThat(prediction.standingSeconds()).isNotNegative();
		} else {
			assertThat(prediction.standingSeconds()).isNull();
		}
		log.info(
				"실제 모델 예측 결과: 입석확률={} 입석={} 입석시간={}초",
				prediction.standingProbability(),
				prediction.standing(),
				prediction.standingSeconds()
		);
	}

	@Test
	void 매핑_코드를_넣으면_학습_범위_밖으로_걸러진다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setDirectory(DIRECTORY.toString());
		StandingPredictor predictor = new PmmlStandingPredictor(StandingModels.load(properties));

		// category_code_mapping_model_a.json 기준 100100129 = 31, 107000087 = 2589 이지만
		// 실제 PMML은 원본 ID를 참조하므로 코드값은 학습 범위 밖이어야 한다.
		StandingPrediction prediction = predictor.predict(new PredictionModelInput(
				"31", "107000087", "107000089", "월", "맑음", 8, false, 420L
		));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.OUT_OF_DOMAIN);
	}

	/** 정확한 값은 아니지만 GC 후 사용량이라 모델이 차지하는 규모를 판단하기에는 충분하다. */
	private static long usedHeapBytes() {
		Runtime runtime = Runtime.getRuntime();
		System.gc();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static List<String> inputNames(StandingModels models) {
		return models.classifier().getInputFields().stream()
				.map(InputField::getName)
				.toList();
	}
}
