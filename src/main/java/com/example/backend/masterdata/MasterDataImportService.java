package com.example.backend.masterdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.backend.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MasterDataImportService {

	private static final Logger log = LoggerFactory.getLogger(MasterDataImportService.class);
	private static final int BATCH_SIZE = 1_000;

	private static final String STOP_UPSERT = """
			insert into stop (
				stop_id, source_operator_code, local_stop_id, ars_id, stop_name,
				district_name, latitude, longitude
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			on duplicate key update
				source_operator_code = values(source_operator_code),
				local_stop_id = values(local_stop_id),
				ars_id = values(ars_id),
				stop_name = values(stop_name),
				district_name = values(district_name),
				latitude = values(latitude),
				longitude = values(longitude)
			""";

	private static final String ROUTE_UPSERT = """
			insert into route (
				route_id, source_operator_code, local_route_id, route_number, route_type,
				start_stop_name, end_stop_name, transport_type_code
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			on duplicate key update
				source_operator_code = values(source_operator_code),
				local_route_id = values(local_route_id),
				route_number = values(route_number),
				route_type = values(route_type),
				start_stop_name = values(start_stop_name),
				end_stop_name = values(end_stop_name),
				transport_type_code = values(transport_type_code)
			""";

	private static final String ROUTE_STOP_UPSERT = """
			insert into route_stop (route_id, stop_id, stop_order, section_distance)
			values (?, ?, ?, ?)
			on duplicate key update
				stop_id = values(stop_id),
				section_distance = values(section_distance)
			""";

	private final JdbcTemplate jdbcTemplate;
	private final String cityName;

	public MasterDataImportService(JdbcTemplate jdbcTemplate, AppProperties appProperties) {
		this.jdbcTemplate = jdbcTemplate;
		this.cityName = appProperties.getMasterData().getCityName();
	}

	public void importFiles(Path stopFile, Path routeFile, Path routeStopFile) throws IOException {
		log.info("Importing stop master data from {}", stopFile);
		Map<String, String> stopIdsByLocalId = importStops(stopFile);
		log.info("Importing route master data from {}", routeFile);
		Map<String, String> routeIdsByLocalId = importRoutes(routeFile);
		log.info("Importing route-stop master data from {}", routeStopFile);
		long routeStopCount = importRouteStops(routeStopFile, routeIdsByLocalId, stopIdsByLocalId);
		log.info(
				"Master data import completed: stops={}, routes={}, routeStops={}",
				stopIdsByLocalId.size(),
				routeIdsByLocalId.size(),
				routeStopCount
		);
	}

	private Map<String, String> importStops(Path file) throws IOException {
		Map<String, String> idsByLocalId = new HashMap<>();
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		long[] importedCount = {0};
		read(file, fields -> {
			if (fields.length < 19 || !cityName.equals(fields[10])
					|| blank(fields[2]) || blank(fields[3]) || blank(fields[4]) || blank(fields[6])) {
				return;
			}
			idsByLocalId.put(sourceLocalKey(fields[2], fields[3]), fields[4]);
			importedCount[0]++;
			batch.add(new Object[] {
					fields[4], fields[2], fields[3], nullIfBlank(fields[16]), fields[6], nullIfBlank(fields[12]),
					decimalOrNull(fields[17]), decimalOrNull(fields[18])
			});
			flushWhenFull(STOP_UPSERT, batch);
			logProgress("stops", importedCount[0], 25_000);
		});
		flush(STOP_UPSERT, batch);
		return idsByLocalId;
	}

	private Map<String, String> importRoutes(Path file) throws IOException {
		Map<String, String> idsByLocalId = new HashMap<>();
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		long[] importedCount = {0};
		read(file, fields -> {
			if (fields.length < 15 || !"B".equals(fields[7])
					|| blank(fields[2]) || blank(fields[3]) || blank(fields[4])) {
				return;
			}
			idsByLocalId.put(sourceLocalKey(fields[2], fields[3]), fields[4]);
			importedCount[0]++;
			batch.add(new Object[] {
					fields[4], fields[2], fields[3], fields[6], fields[7], nullIfBlank(fields[12]),
					nullIfBlank(fields[13]), nullIfBlank(fields[14])
			});
			flushWhenFull(ROUTE_UPSERT, batch);
			logProgress("routes", importedCount[0], 10_000);
		});
		flush(ROUTE_UPSERT, batch);
		return idsByLocalId;
	}

	private long importRouteStops(
			Path file,
			Map<String, String> routeIdsByLocalId,
			Map<String, String> stopIdsByLocalId
	) throws IOException {
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		long[] importedCount = {0};
		read(file, fields -> {
			if (fields.length < 20 || !cityName.equals(fields[13])) {
				return;
			}
			String routeId = routeIdsByLocalId.get(sourceLocalKey(fields[2], fields[3]));
			String stopId = stopIdsByLocalId.get(sourceLocalKey(fields[2], fields[5]));
			Integer stopOrder = integerOrNull(fields[4]);
			if (routeId == null || stopId == null || stopOrder == null) {
				return;
			}
			batch.add(new Object[] {routeId, stopId, stopOrder, integerOrNull(fields[19])});
			importedCount[0]++;
			flushWhenFull(ROUTE_STOP_UPSERT, batch);
			logProgress("routeStops", importedCount[0], 100_000);
		});
		flush(ROUTE_STOP_UPSERT, batch);
		return importedCount[0];
	}

	private void read(Path file, RowConsumer consumer) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				consumer.accept(line.split("\\|", -1));
			}
		}
	}

	private void flushWhenFull(String sql, List<Object[]> batch) {
		if (batch.size() >= BATCH_SIZE) {
			flush(sql, batch);
		}
	}

	private void flush(String sql, List<Object[]> batch) {
		if (batch.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate(sql, batch);
		batch.clear();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String nullIfBlank(String value) {
		return blank(value) ? null : value;
	}

	private static BigDecimal decimalOrNull(String value) {
		try {
			return blank(value) ? null : new BigDecimal(value);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Integer integerOrNull(String value) {
		try {
			return blank(value) ? null : Integer.valueOf(value);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String sourceLocalKey(String sourceOperatorCode, String localId) {
		return sourceOperatorCode + ':' + localId;
	}

	private void logProgress(String target, long count, long interval) {
		if (count > 0 && count % interval == 0) {
			log.info("Master data import progress: {}={}", target, count);
		}
	}

	@FunctionalInterface
	private interface RowConsumer {
		void accept(String[] fields);
	}
}
