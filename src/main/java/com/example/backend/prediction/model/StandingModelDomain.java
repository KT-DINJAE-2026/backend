package com.example.backend.prediction.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.InputField;

/**
 * 모델이 학습한 범주값 목록이다.
 *
 * <p>PMML은 학습에 없던 노선·정류장을 받아도 {@code invalidValueTreatment="asMissing"} 때문에
 * 오류 없이 예측값을 만든다. 근거 없는 값이 사용자에게 나가지 않도록 추론 전에 이 목록으로 먼저
 * 거른다. 승차·하차 정류장 도메인은 구성이 조금 다르므로 각각 확인한다.</p>
 */
public class StandingModelDomain {

	private final Map<String, Set<Object>> discreteDomains;

	private StandingModelDomain(Map<String, Set<Object>> discreteDomains) {
		this.discreteDomains = discreteDomains;
	}

	public static StandingModelDomain from(Evaluator evaluator) {
		Map<String, Set<Object>> domains = new LinkedHashMap<>();
		for (InputField inputField : evaluator.getInputFields()) {
			List<?> values = inputField.getDiscreteDomain();
			if (values == null || values.isEmpty()) {
				// hour처럼 연속형이면 열거된 값이 없다. 이런 피처는 범위 검사 대상이 아니다.
				continue;
			}
			domains.put(inputField.getName(), new HashSet<>(values));
		}
		return new StandingModelDomain(Map.copyOf(domains));
	}

	/** 범주형 피처가 모두 학습 범위 안에 있으면 참이다. */
	public boolean supports(Map<String, Object> features) {
		for (Map.Entry<String, Set<Object>> domain : discreteDomains.entrySet()) {
			Object value = features.get(domain.getKey());
			// 결측을 허용하는 피처는 값이 없어도 예측할 수 있다.
			if (value == null) {
				continue;
			}
			if (!domain.getValue().contains(value)) {
				return false;
			}
		}
		return true;
	}

	/** 검사 대상이 되는 범주형 피처의 값 개수. 모델 교체 시 범위 변화를 확인하는 데 쓴다. */
	public Map<String, Integer> sizes() {
		Map<String, Integer> sizes = new LinkedHashMap<>();
		discreteDomains.forEach((name, values) -> sizes.put(name, values.size()));
		return Map.copyOf(sizes);
	}
}
