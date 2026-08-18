package com.example.backend.prediction.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder;
import org.jpmml.evaluator.visitors.ModelEvaluatorVisitorBattery;

/**
 * PMML 파일을 추론 가능한 {@link Evaluator}로 만든다.
 *
 * <p>모델 하나가 트리 1,000개 규모라 적재 비용이 크다. 요청마다 호출하지 말고 기동 시 한 번만
 * 적재해 공유해야 한다. 적재를 마친 {@code Evaluator}는 스레드 안전하다.</p>
 */
public final class PmmlEvaluatorFactory {

	private PmmlEvaluatorFactory() {
	}

	public static Evaluator load(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new StandingModelException(
					StandingModelException.Reason.CONFIGURATION,
					"모델 파일을 찾을 수 없습니다: " + file.toAbsolutePath()
			);
		}
		try (InputStream stream = Files.newInputStream(file)) {
			return load(stream, file.toAbsolutePath().toString());
		} catch (IOException exception) {
			throw new StandingModelException(
					StandingModelException.Reason.LOAD_FAILURE,
					"모델 파일을 읽을 수 없습니다: " + file.toAbsolutePath(),
					exception
			);
		}
	}

	/** 테스트에서 클래스패스의 소형 모델을 적재할 때도 사용한다. */
	public static Evaluator load(InputStream stream, String description) {
		try {
			Evaluator evaluator = new LoadingModelEvaluatorBuilder()
					// 요소별 위치 정보는 추론에 쓰지 않으므로 버려 메모리를 아낀다.
					.setLocatable(false)
					// 중복 문자열·표현식을 정리해 대형 트리 모델의 상주 메모리를 줄인다.
					.setVisitors(new ModelEvaluatorVisitorBattery())
					.load(stream)
					.build();
			// PMML에 ModelVerification이 있으면 기대값과 대조한다. 없으면 아무 일도 하지 않는다.
			evaluator.verify();
			return evaluator;
		} catch (Exception exception) {
			throw new StandingModelException(
					StandingModelException.Reason.LOAD_FAILURE,
					"PMML을 해석할 수 없습니다: " + description,
					exception
			);
		}
	}
}
