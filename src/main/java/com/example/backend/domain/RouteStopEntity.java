package com.example.backend.domain;

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
@Table(name = "route_stop",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_route_stop_order",
				columnNames = {"route_id", "stop_order"}
		),
		indexes = {
				@Index(name = "idx_route_stop_route_order", columnList = "route_id, stop_order"),
				@Index(name = "idx_route_stop_stop", columnList = "stop_id")
		})
public class RouteStopEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "route_id", nullable = false)
	private RouteEntity route;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "stop_id", nullable = false)
	private StopEntity stop;

	@Column(name = "stop_order", nullable = false)
	private int stopOrder;

	@Column(name = "section_distance")
	private Integer sectionDistance;

	protected RouteStopEntity() {
	}

	public RouteStopEntity(RouteEntity route, StopEntity stop, int stopOrder, Integer sectionDistance) {
		this.route = route;
		this.stop = stop;
		this.stopOrder = stopOrder;
		this.sectionDistance = sectionDistance;
	}

	public Long getId() {
		return id;
	}

	public RouteEntity getRoute() {
		return route;
	}

	public StopEntity getStop() {
		return stop;
	}

	public int getStopOrder() {
		return stopOrder;
	}

	public Integer getSectionDistance() {
		return sectionDistance;
	}
}
