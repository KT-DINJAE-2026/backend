package com.example.backend.masterdata;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.backend.config.AppProperties;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code app.master-data.import-enabled=true}일 때만 기반정보 적재를 실행하는 시작 훅이다.
 *
 * <p>대용량 파일을 평소 서버 시작마다 읽지 않도록 기본값은 비활성화되어 있다.
 * STTN, ROUTE, ROUTESTTN은 반드시 같은 기준일 파일을 사용해야 한다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.master-data", name = "import-enabled", havingValue = "true")
public class MasterDataImportRunner implements ApplicationRunner {

	private final MasterDataImportService importService;
	private final AppProperties appProperties;

	public MasterDataImportRunner(MasterDataImportService importService, AppProperties appProperties) {
		this.importService = importService;
		this.appProperties = appProperties;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		AppProperties.MasterData properties = appProperties.getMasterData();
		Path stopFile = requiredFile(properties.getStopFile(), "MASTER_STOP_FILE");
		Path routeFile = requiredFile(properties.getRouteFile(), "MASTER_ROUTE_FILE");
		Path routeStopFile = requiredFile(properties.getRouteStopFile(), "MASTER_ROUTE_STOP_FILE");
		importService.importFiles(stopFile, routeFile, routeStopFile);
	}

	private Path requiredFile(String configuredPath, String environmentName) {
		if (configuredPath == null || configuredPath.isBlank()) {
			throw new IllegalStateException(environmentName + " 환경변수가 필요합니다.");
		}
		Path path = Path.of(configuredPath);
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException(environmentName + " 파일을 찾을 수 없습니다: " + path);
		}
		return path;
	}
}
