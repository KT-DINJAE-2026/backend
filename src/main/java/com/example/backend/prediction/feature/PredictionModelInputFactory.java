package com.example.backend.prediction.feature;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

import com.example.backend.headway.HeadwayProvider;
import com.example.backend.holiday.HolidayProvider;
import com.example.backend.weather.WeatherProvider;

import org.springframework.stereotype.Component;

/** 차량의 승차 예정 시각과 외부 피처를 모델 입력 형식으로 조합한다. */
@Component
public class PredictionModelInputFactory {

	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

	private final HolidayProvider holidayProvider;
	private final WeatherProvider weatherProvider;
	private final HeadwayProvider headwayProvider;

	public PredictionModelInputFactory(
			HolidayProvider holidayProvider,
			WeatherProvider weatherProvider,
			HeadwayProvider headwayProvider
	) {
		this.holidayProvider = holidayProvider;
		this.weatherProvider = weatherProvider;
		this.headwayProvider = headwayProvider;
	}

	/**
	 * 날씨와 공휴일 및 서울시 계획 배차간격을 조회해 운영용 입력을 만든다.
	 *
	 * <p>배차간격 원본에는 표준 노선 ID가 없어 {@code routeNumber}로 조회한다. 파일의 0·빈값 또는
	 * 미매핑 노선은 모델이 허용하는 결측({@code null})으로 유지한다.</p>
	 */
	public PredictionModelInput createForStop(
			String routeId,
			String routeNumber,
			String boardStopId,
			String alightStopId,
			BigDecimal boardLatitude,
			BigDecimal boardLongitude,
			OffsetDateTime boardingTime
	) {
		Objects.requireNonNull(boardingTime, "boardingTime은 null일 수 없습니다.");
		ZonedDateTime seoulBoardingTime = boardingTime.atZoneSameInstant(SEOUL_ZONE);
		boolean holiday = holidayProvider.isHoliday(seoulBoardingTime.toLocalDate());
		String weather = weatherProvider.weatherAt(boardLatitude, boardLongitude, boardingTime);
		Long headwaySec = headwayProvider.headwaySeconds(
				routeNumber,
				seoulBoardingTime.toLocalDate(),
				holiday
		);
		return createPrepared(
				routeId,
				boardStopId,
				alightStopId,
				seoulBoardingTime,
				weather,
				holiday,
				headwaySec
		);
	}

	/**
	 * 정류장 좌표의 시간별 예보까지 조회해 운영용 입력을 만든다.
	 *
	 * <p>{@code boardingTime}은 요청 시각이 아니라 TOPIS 도착시간을 더한 차량별 예상 승차 시각이어야
	 * 학습 데이터의 {@code board_datetime}과 의미가 맞는다.</p>
	 */
	public PredictionModelInput createForStop(
			String routeId,
			String boardStopId,
			String alightStopId,
			BigDecimal boardLatitude,
			BigDecimal boardLongitude,
			OffsetDateTime boardingTime,
			Long headwaySec
	) {
		String weather = weatherProvider.weatherAt(boardLatitude, boardLongitude, boardingTime);
		return create(
				routeId,
				boardStopId,
				alightStopId,
				boardingTime,
				weather,
				headwaySec
		);
	}

	public PredictionModelInput create(
			String routeId,
			String boardStopId,
			String alightStopId,
			OffsetDateTime boardingTime,
			String weather,
			Long headwaySec
	) {
		Objects.requireNonNull(boardingTime, "boardingTime은 null일 수 없습니다.");
		// 학습 데이터의 board_datetime과 동일하게 실제 승차 예정 시각을 서울 시간으로 변환한다.
		ZonedDateTime seoulBoardingTime = boardingTime.atZoneSameInstant(SEOUL_ZONE);
		boolean holiday = holidayProvider.isHoliday(seoulBoardingTime.toLocalDate());
		return createPrepared(
				routeId,
				boardStopId,
				alightStopId,
				seoulBoardingTime,
				weather,
				holiday,
				headwaySec
		);
	}

	private PredictionModelInput createPrepared(
			String routeId,
			String boardStopId,
			String alightStopId,
			ZonedDateTime seoulBoardingTime,
			String weather,
			boolean holiday,
			Long headwaySec
	) {
		return new PredictionModelInput(
				routeId,
				boardStopId,
				alightStopId,
				weekday(seoulBoardingTime.getDayOfWeek()),
				weather,
				seoulBoardingTime.getHour(),
				holiday,
				headwaySec
		);
	}

	private static String weekday(DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> "월";
			case TUESDAY -> "화";
			case WEDNESDAY -> "수";
			case THURSDAY -> "목";
			case FRIDAY -> "금";
			case SATURDAY -> "토";
			case SUNDAY -> "일";
		};
	}
}
