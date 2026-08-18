package com.example.backend.prediction.model;

import java.nio.file.Path;

import com.example.backend.config.AppProperties;

import org.jpmml.evaluator.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 입석 여부 분류(모델 A)와 입석시간 회귀(모델 B)를 함께 보관한다.
 *
 * <p>모델 파일은 저장소에 없으므로 파일을 받지 못한 환경에서는 비어 있는 상태로 만들어지고,
 * {@link #isAvailable()}이 {@code false}가 된다. 호출자는 이 값을 확인해 예측 대신 데이터 부족
 * 응답을 내려야 한다.</p>
 */
public class StandingModels {

	private static final Logger log = LoggerFactory.getLogger(StandingModels.class);

	private final Evaluator classifier;
	private final Evaluator regressor;

	public StandingModels(Evaluator classifier, Evaluator regressor) {
		this.classifier = classifier;
		this.regressor = regressor;
	}

	/** 설정이 꺼져 있으면 비어 있는 상태로, 켜져 있으면 즉시 적재해 실패를 기동 시점에 드러낸다. */
	public static StandingModels load(AppProperties.Model properties) {
		if (!properties.isEnabled()) {
			log.info("입석 예측 모델이 비활성화되어 있습니다. 여정 예측은 모델 없이 동작합니다.");
			return new StandingModels(null, null);
		}
		Path directory = Path.of(properties.getDirectory());
		Evaluator classifier = PmmlEvaluatorFactory.load(directory.resolve(properties.getClassifierFile()));
		Evaluator regressor = PmmlEvaluatorFactory.load(directory.resolve(properties.getRegressorFile()));
		log.info(
				"입석 예측 모델을 적재했습니다. directory={} classifier={} regressor={}",
				directory.toAbsolutePath(),
				properties.getClassifierFile(),
				properties.getRegressorFile()
		);
		return new StandingModels(classifier, regressor);
	}

	public boolean isAvailable() {
		return classifier != null && regressor != null;
	}

	/** 입석 여부 분류 모델. 확률은 {@code probability(1)} 출력 필드로 얻는다. */
	public Evaluator classifier() {
		return required(classifier);
	}

	/** 입석 지속시간 회귀 모델. 예측값이 곧 초 단위 입석시간이다. */
	public Evaluator regressor() {
		return required(regressor);
	}

	private Evaluator required(Evaluator evaluator) {
		if (evaluator == null) {
			throw new StandingModelException(
					StandingModelException.Reason.CONFIGURATION,
					"입석 예측 모델이 적재되지 않았습니다. app.model 설정과 모델 파일을 확인해 주세요."
			);
		}
		return evaluator;
	}
}
