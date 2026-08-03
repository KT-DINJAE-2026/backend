package com.example.backend.prediction;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.backend.config.AppProperties;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prediction", name = "import-enabled", havingValue = "true")
public class PredictionImportRunner implements ApplicationRunner {

	private final PredictionImportService importService;
	private final AppProperties appProperties;

	public PredictionImportRunner(PredictionImportService importService, AppProperties appProperties) {
		this.importService = importService;
		this.appProperties = appProperties;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		String configuredPath = appProperties.getPrediction().getImportFile();
		if (configuredPath == null || configuredPath.isBlank()) {
			throw new IllegalStateException("PREDICTION_IMPORT_FILE 환경변수가 필요합니다.");
		}
		Path path = Path.of(configuredPath);
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException("PREDICTION_IMPORT_FILE을 찾을 수 없습니다: " + path);
		}
		importService.importFile(path);
	}
}
