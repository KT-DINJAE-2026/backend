package com.example.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "route", uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_route_source_local",
				columnNames = {"source_operator_code", "local_route_id"}
		)
}, indexes = {
		@Index(name = "idx_route_number", columnList = "route_number")
})
public class RouteEntity {

	@Id
	@Column(name = "route_id", length = 20, nullable = false)
	private String id;

	@Column(name = "source_operator_code", length = 10, nullable = false)
	private String sourceOperatorCode;

	@Column(name = "local_route_id", length = 30, nullable = false)
	private String localRouteId;

	@Column(name = "route_number", length = 30, nullable = false)
	private String number;

	@Column(name = "route_type", length = 5, nullable = false)
	private String type;

	@Column(name = "start_stop_name", length = 150)
	private String startStopName;

	@Column(name = "end_stop_name", length = 150)
	private String endStopName;

	@Column(name = "transport_type_code", length = 10)
	private String transportTypeCode;

	protected RouteEntity() {
	}

	public RouteEntity(
			String id,
			String sourceOperatorCode,
			String localRouteId,
			String number,
			String type,
			String startStopName,
			String endStopName,
			String transportTypeCode
	) {
		this.id = id;
		this.sourceOperatorCode = sourceOperatorCode;
		this.localRouteId = localRouteId;
		this.number = number;
		this.type = type;
		this.startStopName = startStopName;
		this.endStopName = endStopName;
		this.transportTypeCode = transportTypeCode;
	}

	public String getId() {
		return id;
	}

	public String getLocalRouteId() {
		return localRouteId;
	}

	public String getSourceOperatorCode() {
		return sourceOperatorCode;
	}

	public String getNumber() {
		return number;
	}

	public String getType() {
		return type;
	}

	public String getStartStopName() {
		return startStopName;
	}

	public String getEndStopName() {
		return endStopName;
	}

	public String getTransportTypeCode() {
		return transportTypeCode;
	}
}
