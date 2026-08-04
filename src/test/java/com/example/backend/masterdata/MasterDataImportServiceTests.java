package com.example.backend.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.backend.repository.RouteRepository;
import com.example.backend.repository.RouteStopRepository;
import com.example.backend.repository.StopRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** DAT 컬럼 위치와 로컬 ID에서 표준 ID로 이어지는 적재 관계를 작은 파일로 검증한다. */
@SpringBootTest
@Transactional
class MasterDataImportServiceTests {

	@Autowired
	private MasterDataImportService importService;

	@Autowired
	private StopRepository stopRepository;

	@Autowired
	private RouteRepository routeRepository;

	@Autowired
	private RouteStopRepository routeStopRepository;

	@TempDir
	private Path tempDirectory;

	@Test
	void importsPipeDelimitedMasterDataAndConnectsStandardIds() throws Exception {
		Path stopFile = tempDirectory.resolve("STTN.dat");
		Path routeFile = tempDirectory.resolve("ROUTE.dat");
		Path routeStopFile = tempDirectory.resolve("ROUTESTTN.dat");

		Files.writeString(stopFile, String.join("\n",
				stopRow("LOCAL-ORIGIN", "121000019", "고속터미널", "22019"),
				stopRow("LOCAL-DEST", "121000021", "신반포역.세화여중고", "22021")
		));
		Files.writeString(routeFile, routeRow());
		Files.writeString(routeStopFile, String.join("\n",
				routeStopRow("LOCAL-ROUTE", 10, "LOCAL-ORIGIN"),
				routeStopRow("LOCAL-ROUTE", 20, "LOCAL-DEST")
		));

		importService.importFiles(stopFile, routeFile, routeStopFile);

		assertThat(stopRepository.findById("121000019")).isPresent();
		assertThat(routeRepository.findById("100100027")).isPresent();
		assertThat(routeStopRepository.count()).isEqualTo(2);
		assertThat(routeRepository.findDirectRoutes("121000019", "121000021"))
				.extracting(route -> route.getNumber())
				.containsExactly("148");
	}

	private String stopRow(String localId, String stopId, String name, String arsId) {
		// 운영 파일과 같은 인덱스에만 값을 넣어 포맷 변경을 테스트가 감지하게 한다.
		String[] fields = new String[22];
		fields[0] = "20250401";
		fields[2] = "11100";
		fields[3] = localId;
		fields[4] = stopId;
		fields[6] = name;
		fields[10] = "서울특별시";
		fields[12] = "서초구";
		fields[16] = arsId;
		fields[17] = "37.500000";
		fields[18] = "127.000000";
		return join(fields);
	}

	private String routeRow() {
		String[] fields = new String[18];
		fields[0] = "20250401";
		fields[2] = "11100";
		fields[3] = "LOCAL-ROUTE";
		fields[4] = "100100027";
		fields[6] = "148";
		fields[7] = "B";
		fields[12] = "방배동";
		fields[13] = "번동";
		fields[14] = "115";
		return join(fields);
	}

	private String routeStopRow(String localRouteId, int stopOrder, String localStopId) {
		String[] fields = new String[21];
		fields[0] = "20250401";
		fields[2] = "11100";
		fields[3] = localRouteId;
		fields[4] = String.valueOf(stopOrder);
		fields[5] = localStopId;
		fields[13] = "서울특별시";
		fields[19] = "300";
		return join(fields);
	}

	private String join(String[] fields) {
		for (int index = 0; index < fields.length; index++) {
			if (fields[index] == null) {
				fields[index] = "";
			}
		}
		return String.join("|", fields);
	}
}
