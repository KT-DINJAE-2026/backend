package com.example.backend.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "prediction",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_prediction_lookup",
				columnNames = {
						"route_id", "board_stop_id", "alight_stop_id", "weekday",
						"prediction_hour", "weather", "usertype_code"
				}
		),
		indexes = @Index(
				name = "idx_prediction_lookup",
				columnList = "board_stop_id, alight_stop_id, weekday, prediction_hour, weather, usertype_code"
		))
public class PredictionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "route_id", nullable = false)
	private RouteEntity route;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "board_stop_id", nullable = false)
	private StopEntity boardingStop;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "alight_stop_id", nullable = false)
	private StopEntity alightingStop;

	@Column(name = "weekday", length = 1, nullable = false)
	private String weekday;

	@Column(name = "prediction_hour", nullable = false)
	private int hour;

	@Column(name = "weather", length = 20, nullable = false)
	private String weather;

	@Column(name = "usertype_code", length = 2, nullable = false)
	private String userTypeCode;

	@Column(name = "standing_seconds")
	private Integer standingSeconds;

	@Column(name = "risk_level", length = 10)
	private String riskLevel;

	@Column(name = "model_confidence", precision = 5, scale = 4)
	private BigDecimal modelConfidence;

	@Column(name = "boarding_sample_count", nullable = false)
	private int boardingSampleCount;

	@Column(name = "od_sample_count", nullable = false)
	private int odSampleCount;

	@Column(name = "travel_seconds", nullable = false)
	private int travelSeconds;

	@Column(name = "model_version", length = 50)
	private String modelVersion;

	protected PredictionEntity() {
	}

	public PredictionEntity(
			RouteEntity route,
			StopEntity boardingStop,
			StopEntity alightingStop,
			String weekday,
			int hour,
			String weather,
			String userTypeCode,
			Integer standingSeconds,
			String riskLevel,
			BigDecimal modelConfidence,
			int boardingSampleCount,
			int odSampleCount,
			int travelSeconds,
			String modelVersion
	) {
		this.route = route;
		this.boardingStop = boardingStop;
		this.alightingStop = alightingStop;
		this.weekday = weekday;
		this.hour = hour;
		this.weather = weather;
		this.userTypeCode = userTypeCode;
		this.standingSeconds = standingSeconds;
		this.riskLevel = riskLevel;
		this.modelConfidence = modelConfidence;
		this.boardingSampleCount = boardingSampleCount;
		this.odSampleCount = odSampleCount;
		this.travelSeconds = travelSeconds;
		this.modelVersion = modelVersion;
	}

	public RouteEntity getRoute() { return route; }
	public StopEntity getBoardingStop() { return boardingStop; }
	public StopEntity getAlightingStop() { return alightingStop; }
	public String getWeekday() { return weekday; }
	public int getHour() { return hour; }
	public String getWeather() { return weather; }
	public String getUserTypeCode() { return userTypeCode; }
	public Integer getStandingSeconds() { return standingSeconds; }
	public String getRiskLevel() { return riskLevel; }
	public BigDecimal getModelConfidence() { return modelConfidence; }
	public int getBoardingSampleCount() { return boardingSampleCount; }
	public int getOdSampleCount() { return odSampleCount; }
	public int getTravelSeconds() { return travelSeconds; }
	public String getModelVersion() { return modelVersion; }
}
