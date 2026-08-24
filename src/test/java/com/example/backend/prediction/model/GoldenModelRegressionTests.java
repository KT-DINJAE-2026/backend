package com.example.backend.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.backend.config.AppProperties;
import com.example.backend.prediction.feature.PredictionModelInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.InputField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * AI팀이 전달한 golden test 샘플로 Python 원본과 Java 추론의 동등성을 확인한다.
 *
 * <p>샘플의 {@code raw_input}(원본 ID·한글 범주)을 우리 운영 경로 그대로
 * {@link StandingFeatureAdapter}로 변환해 넣는다. {@code encoded_input_*}은 매핑 JSON 기반
 * 정수 코드라서 사용하지 않는다(models/README.md "매핑 JSON을 쓰지 않는 이유").</p>
 *
 * <p>기대값 대조는 두 층위로 한다. ① 모델 원출력: 분류 확률과 회귀 초를 모델 실행 결과
 * 그대로 비교한다(회귀는 음수 포함 전 구간, 입석 여부와 무관하게 20건 전부). ② 예측기 의미:
 * {@link PmmlStandingPredictor}의 임계값 판정과 음수 클램프가 기대 판정과 일치하는지 본다.
 * 허용 오차는 기대값 반올림 자릿수(확률 6자리, 초 2자리)에 맞춘다.</p>
 */
@EnabledIf("goldenFixturesPresent")
class GoldenModelRegressionTests {

	private static final Path DIRECTORY = Path.of("models");
	private static final Path SAMPLES = DIRECTORY.resolve("golden_test_samples.json");
	private static final double PROBABILITY_TOLERANCE = 1e-4;
	private static final double SECONDS_TOLERANCE = 0.02;

	private static StandingModels models;
	private static List<JsonNode> samples;

	static boolean goldenFixturesPresent() {
		return Files.isRegularFile(DIRECTORY.resolve("model_a.pmml"))
				&& Files.isRegularFile(DIRECTORY.resolve("model_b.pmml"))
				&& Files.isRegularFile(SAMPLES);
	}

	@BeforeAll
	static void loadFixtures() throws Exception {
		AppProperties.Model properties = new AppProperties.Model();
		properties.setDirectory(DIRECTORY.toString());
		models = StandingModels.load(properties);

		JsonNode root = new ObjectMapper().readTree(SAMPLES.toFile());
		samples = List.copyOf(root.valueStream().toList());
	}

	@Test
	void 골든_샘플_전건의_분류_확률이_파이썬_기대값과_일치한다() {
		for (JsonNode sample : samples) {
			double probability = evaluateSingle(
					models.classifier(),
					PmmlStandingPredictor.STANDING_PROBABILITY_FIELD,
					features(sample)
			);
			assertThat(probability)
					.as("%s probability(1)", sampleId(sample))
					.isCloseTo(expected(sample, "model_a_proba_standing"), within(PROBABILITY_TOLERANCE));
		}
	}

	@Test
	void 골든_샘플_전건의_회귀_초가_음수_포함_원출력_기준으로_일치한다() {
		for (JsonNode sample : samples) {
			Evaluator regressor = models.regressor();
			double seconds = evaluateSingle(
					regressor,
					regressor.getTargetFields().getFirst().getName(),
					features(sample)
			);
			assertThat(seconds)
					.as("%s standing seconds", sampleId(sample))
					.isCloseTo(
							expected(sample, "model_b_predicted_standing_seconds"),
							within(SECONDS_TOLERANCE)
					);
		}
	}

	@Test
	void 예측기의_임계값_판정과_음수_클램프가_기대_판정과_일치한다() {
		PmmlStandingPredictor predictor = new PmmlStandingPredictor(models);
		for (JsonNode sample : samples) {
			boolean expectedStanding =
					"Y".equals(sample.get("expected_output").get("model_a_is_standing").asText());
			StandingPrediction prediction = predictor.predict(input(sample));

			assertThat(prediction.isPredicted()).as("%s 도메인 안 입력", sampleId(sample)).isTrue();
			assertThat(prediction.standing()).as("%s 입석 판정", sampleId(sample)).isEqualTo(expectedStanding);
			if (expectedStanding) {
				double clamped = Math.max(0.0d, expected(sample, "model_b_predicted_standing_seconds"));
				assertThat(prediction.standingSeconds())
						.as("%s 클램프된 입석 초", sampleId(sample))
						.isCloseTo(clamped, within(SECONDS_TOLERANCE));
			} else {
				assertThat(prediction.standingSeconds()).as("%s 미입석 초", sampleId(sample)).isNull();
			}
		}
	}

	private static Map<String, Object> features(JsonNode sample) {
		return StandingFeatureAdapter.toModelFeatures(input(sample));
	}

	private static PredictionModelInput input(JsonNode sample) {
		JsonNode raw = sample.get("raw_input");
		return new PredictionModelInput(
				raw.get("route_id").asText(),
				raw.get("board_stop_id").asText(),
				raw.get("alight_stop_id").asText(),
				raw.get("weekday").asText(),
				raw.get("weather").asText(),
				raw.get("hour").asInt(),
				raw.get("is_holiday").asInt() == 1,
				raw.get("headway_sec").isNull() ? null : raw.get("headway_sec").asLong()
		);
	}

	private static double evaluateSingle(Evaluator evaluator, String field, Map<String, Object> features) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		for (InputField inputField : evaluator.getInputFields()) {
			arguments.put(inputField.getName(), inputField.prepare(features.get(inputField.getName())));
		}
		Object decoded = EvaluatorUtil.decode(evaluator.evaluate(arguments).get(field));
		assertThat(decoded).as("%s 출력", field).isInstanceOf(Number.class);
		return ((Number) decoded).doubleValue();
	}

	private static double expected(JsonNode sample, String field) {
		return sample.get("expected_output").get(field).asDouble();
	}

	private static String sampleId(JsonNode sample) {
		return sample.get("sample_id").asText();
	}
}
