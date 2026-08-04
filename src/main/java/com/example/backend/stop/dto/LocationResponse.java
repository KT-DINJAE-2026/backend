package com.example.backend.stop.dto;

import java.math.BigDecimal;

/** 카카오 로드뷰 조회에 사용하는 WGS84 위도·경도이다. */
public record LocationResponse(
		BigDecimal latitude,
		BigDecimal longitude
) {
}
