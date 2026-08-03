package com.example.backend.stop.dto;

import java.math.BigDecimal;

public record LocationResponse(
		BigDecimal latitude,
		BigDecimal longitude
) {
}
