package com.example.backend.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "stop", uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_stop_source_local",
				columnNames = {"source_operator_code", "local_stop_id"}
		)
}, indexes = {
		@Index(name = "idx_stop_name", columnList = "stop_name"),
		@Index(name = "idx_stop_ars_id", columnList = "ars_id")
})
public class StopEntity {

	@Id
	@Column(name = "stop_id", length = 20, nullable = false)
	private String id;

	@Column(name = "source_operator_code", length = 10, nullable = false)
	private String sourceOperatorCode;

	@Column(name = "local_stop_id", length = 30, nullable = false)
	private String localStopId;

	@Column(name = "ars_id", length = 10)
	private String arsId;

	@Column(name = "stop_name", length = 150, nullable = false)
	private String name;

	@Column(name = "district_name", length = 50)
	private String districtName;

	@Column(name = "latitude", precision = 12, scale = 8)
	private BigDecimal latitude;

	@Column(name = "longitude", precision = 12, scale = 8)
	private BigDecimal longitude;

	protected StopEntity() {
	}

	public StopEntity(
			String id,
			String sourceOperatorCode,
			String localStopId,
			String arsId,
			String name,
			String districtName,
			BigDecimal latitude,
			BigDecimal longitude
	) {
		this.id = id;
		this.sourceOperatorCode = sourceOperatorCode;
		this.localStopId = localStopId;
		this.arsId = arsId;
		this.name = name;
		this.districtName = districtName;
		this.latitude = latitude;
		this.longitude = longitude;
	}

	public String getId() {
		return id;
	}

	public String getLocalStopId() {
		return localStopId;
	}

	public String getSourceOperatorCode() {
		return sourceOperatorCode;
	}

	public String getArsId() {
		return arsId;
	}

	public String getName() {
		return name;
	}

	public String getDistrictName() {
		return districtName;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}
}
