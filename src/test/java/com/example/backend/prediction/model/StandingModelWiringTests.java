package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.backend.prediction.feature.PredictionModelInput;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 운영과 같은 경로로 모델이 배선되는지 확인한다.
 *
 * <p>{@code app.model.*} 설정 바인딩, 기동 시 1회 적재, 예측기 주입까지를 한 번에 확인한다.
 * 실제 모델 파일이 있는 환경에서만 실행된다.</p>
 */
@SpringBootTest(properties = {
		"app.model.enabled=true",
		"app.model.directory=models"
})
@EnabledIf("realModelsPresent")
class StandingModelWiringTests {

	static boolean realModelsPresent() {
		return Files.isRegularFile(Path.of("models", "model_a.pmml"))
				&& Files.isRegularFile(Path.of("models", "model_b.pmml"));
	}

	@Autowired
	private StandingModels models;

	@Autowired
	private StandingPredictor predictor;

	@Test
	void 설정한_경로의_모델이_컨텍스트에_적재된다() {
		assertThat(models.isAvailable()).isTrue();
	}

	@Test
	void 주입받은_예측기가_실제_예측을_수행한다() {
		StandingPrediction prediction = predictor.predict(new PredictionModelInput(
				"100100129", "107000087", "107000089", "화", "비", 18, false, 600L
		));

		assertThat(prediction.status()).isEqualTo(StandingPredictionStatus.PREDICTED);
		assertThat(prediction.standingProbability()).isBetween(0.0d, 1.0d);
	}
}
