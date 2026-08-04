package com.example.backend.prediction.dto;

import com.example.backend.prediction.PredictionConfidence;

/**
 * FE가 예측 신뢰도 표시를 결정하는 구조화된 근거 정보이다.
 * 향후 기준 시각·요일·날씨·표본 수가 필요하면 문자열 설명 대신 이 객체에 필드를 추가한다.
 */
public record PredictionBasisResponse(PredictionConfidence confidence) {
}
