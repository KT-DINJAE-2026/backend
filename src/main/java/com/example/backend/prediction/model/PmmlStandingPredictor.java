package com.example.backend.prediction.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.backend.prediction.feature.PredictionModelInput;

import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.InputField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PMML 모델 A/B를 순서대로 실행해 입석 예측을 만든다.
 *
 * <p>모델 A가 입석으로 판정한 경우에만 모델 B를 실행한다. 미입석 승객의 입석시간은 학습 대상이
 * 아니어서 의미가 없기 때문이다.</p>
 */
@Component
public class PmmlStandingPredictor implements StandingPredictor {

	/** 모델 A 출력에서 입석 확률을 담는 필드. */
	static final String STANDING_PROBABILITY_FIELD = "probability(1)";

	/** 입석 판정 임계값. AI팀과 합의한 모델 출력 계약이다. */
	static final double STANDING_THRESHOLD = 0.5d;

	private static final Logger log = LoggerFactory.getLogger(PmmlStandingPredictor.class);

	private final StandingModels models;
	private final StandingModelDomain domain;

	public PmmlStandingPredictor(StandingModels models) {
		this.models = models;
		this.domain = models.isAvailable() ? StandingModelDomain.from(models.classifier()) : null;
		if (domain != null) {
			log.info("입석 예측 모델의 학습 범위: {}", domain.sizes());
		}
	}

	@Override
	public StandingPrediction predict(PredictionModelInput input) {
		if (!models.isAvailable()) {
			return StandingPrediction.unavailable(StandingPredictionStatus.MODEL_UNAVAILABLE);
		}

		Map<String, Object> features = StandingFeatureAdapter.toModelFeatures(input);
		if (!domain.supports(features)) {
			return StandingPrediction.unavailable(StandingPredictionStatus.OUT_OF_DOMAIN);
		}

		double standingProbability = standingProbability(features);
		if (standingProbability < STANDING_THRESHOLD) {
			return StandingPrediction.seated(standingProbability);
		}
		return StandingPrediction.standing(standingProbability, standingSeconds(features));
	}

	private double standingProbability(Map<String, Object> features) {
		Evaluator evaluator = models.classifier();
		Map<String, ?> results = evaluate(evaluator, features, "입석 여부 분류");
		Object probability = EvaluatorUtil.decode(results.get(STANDING_PROBABILITY_FIELD));
		if (!(probability instanceof Number number)) {
			throw new StandingModelException(
					StandingModelException.Reason.EVALUATION_FAILURE,
					"모델 A가 " + STANDING_PROBABILITY_FIELD + " 출력을 반환하지 않았습니다."
			);
		}
		return number.doubleValue();
	}

	private double standingSeconds(Map<String, Object> features) {
		Evaluator evaluator = models.regressor();
		Map<String, ?> results = evaluate(evaluator, features, "입석시간 회귀");
		String targetName = evaluator.getTargetFields().getFirst().getName();
		Object seconds = EvaluatorUtil.decode(results.get(targetName));
		if (!(seconds instanceof Number number)) {
			throw new StandingModelException(
					StandingModelException.Reason.EVALUATION_FAILURE,
					"모델 B가 입석시간을 반환하지 않았습니다."
			);
		}
		// 회귀 모델은 음수를 낼 수 있다. 물리적으로 불가능한 값이므로 0초로 맞춘다.
		// 전체 여정 시간을 넘는 값의 보정은 여정 정보를 가진 상위 서비스가 담당한다.
		return Math.max(0.0d, number.doubleValue());
	}

	private Map<String, ?> evaluate(Evaluator evaluator, Map<String, Object> features, String description) {
		try {
			return evaluator.evaluate(arguments(evaluator, features));
		} catch (StandingModelException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new StandingModelException(
					StandingModelException.Reason.EVALUATION_FAILURE,
					description + " 모델 실행에 실패했습니다.",
					exception
			);
		}
	}

	/** PMML이 선언한 자료형·결측 규칙에 맞춰 입력을 준비한다. */
	private static Map<String, Object> arguments(Evaluator evaluator, Map<String, Object> features) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		for (InputField inputField : evaluator.getInputFields()) {
			arguments.put(inputField.getName(), inputField.prepare(features.get(inputField.getName())));
		}
		return arguments;
	}
}
