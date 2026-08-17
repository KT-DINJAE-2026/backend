package com.example.backend.prediction.model;

import java.io.InputStream;

import org.jpmml.evaluator.Evaluator;

/**
 * 실제 PMML은 저장소에 없으므로 테스트는 같은 입력·출력 계약을 가진 소형 모델을 사용한다.
 *
 * @see <a href="file:../../../../../../../models/README.md">models/README.md</a>
 */
public final class TestStandingModels {

	public static final String CLASSIFIER_RESOURCE = "/pmml/standing-classifier.pmml";
	public static final String REGRESSOR_RESOURCE = "/pmml/standing-regressor.pmml";

	private TestStandingModels() {
	}

	public static StandingModels load() {
		return new StandingModels(evaluator(CLASSIFIER_RESOURCE), evaluator(REGRESSOR_RESOURCE));
	}

	public static Evaluator evaluator(String resource) {
		try (InputStream stream = TestStandingModels.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("테스트 PMML 리소스를 찾을 수 없습니다: " + resource);
			}
			return PmmlEvaluatorFactory.load(stream, resource);
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("테스트 PMML 리소스를 읽을 수 없습니다: " + resource, exception);
		}
	}
}
