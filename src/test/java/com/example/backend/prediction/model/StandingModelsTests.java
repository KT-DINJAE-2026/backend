package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.example.backend.config.AppProperties;
import com.example.backend.prediction.feature.PredictionModelInput;

import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.InputField;
import org.junit.jupiter.api.Test;

class StandingModelsTests {

	@Test
	void 비활성화하면_모델을_적재하지_않고_사용_시점에_설정_오류를_알린다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setEnabled(false);

		StandingModels models = StandingModels.load(properties);

		assertThat(models.isAvailable()).isFalse();
		assertThatThrownBy(models::classifier)
				.isInstanceOf(StandingModelException.class)
				.extracting(exception -> ((StandingModelException) exception).reason())
				.isEqualTo(StandingModelException.Reason.CONFIGURATION);
	}

	@Test
	void 모델_파일이_없으면_기동_시점에_실패한다() {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setEnabled(true);
		properties.setDirectory("no-such-directory");

		// 파일이 없는 채로 조용히 기동해 요청 시점에 실패하는 것보다 기동을 막는 편이 낫다.
		assertThatThrownBy(() -> StandingModels.load(properties))
				.isInstanceOf(StandingModelException.class)
				.hasMessageContaining(Path.of("no-such-directory", "model_a.pmml").toString())
				.extracting(exception -> ((StandingModelException) exception).reason())
				.isEqualTo(StandingModelException.Reason.CONFIGURATION);
	}

	@Test
	void 적재한_분류_모델은_여덟_개_입력_피처를_학습_순서대로_노출한다() {
		Evaluator classifier = TestStandingModels.evaluator(TestStandingModels.CLASSIFIER_RESOURCE);

		List<String> inputNames = classifier.getInputFields().stream()
				.map(InputField::getName)
				.toList();

		assertThat(inputNames).isEqualTo(PredictionModelInput.FEATURE_NAMES);
	}

	@Test
	void 분류_모델은_입석_확률_출력_필드를_제공한다() {
		Evaluator classifier = TestStandingModels.evaluator(TestStandingModels.CLASSIFIER_RESOURCE);

		List<String> outputNames = classifier.getOutputFields().stream()
				.map(field -> field.getName())
				.toList();

		assertThat(outputNames).contains("probability(1)");
	}

	@Test
	void 회귀_모델은_초_단위_예측을_위한_연속형_목표를_가진다() {
		Evaluator regressor = TestStandingModels.evaluator(TestStandingModels.REGRESSOR_RESOURCE);

		assertThat(regressor.getTargetFields()).hasSize(1);
		assertThat(regressor.getInputFields().stream().map(InputField::getName).toList())
				.isEqualTo(PredictionModelInput.FEATURE_NAMES);
	}

	@Test
	void 두_모델을_모두_적재하면_사용_가능_상태가_된다() {
		StandingModels models = TestStandingModels.load();

		assertThat(models.isAvailable()).isTrue();
		assertThat(models.classifier()).isNotNull();
		assertThat(models.regressor()).isNotNull();
	}
}
