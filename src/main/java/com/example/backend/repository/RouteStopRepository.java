package com.example.backend.repository;

import com.example.backend.domain.RouteStopEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopRepository extends JpaRepository<RouteStopEntity, Long> {
}
