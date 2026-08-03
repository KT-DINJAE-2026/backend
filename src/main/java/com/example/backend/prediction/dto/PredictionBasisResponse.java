package com.example.backend.prediction.dto;

import com.example.backend.prediction.PredictionConfidence;

public record PredictionBasisResponse(PredictionConfidence confidence) {
}
