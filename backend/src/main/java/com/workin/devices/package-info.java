/**
 * Attendance-device ingestion (ADR-0006; D-023 core, D-156 ZKTeco adapter).
 *
 * <p>A third root next to {@code com.workin.backend} (the Postgres-era
 * application) and {@code com.workin.legacy} (the PHP parity port). It is
 * neither: it is new Phase-1 functionality that persists through
 * {@code legacyDataSource} with {@code JdbcTemplate}, so it lives outside
 * both scan roots and is reached only by {@code LegacyPersistenceConfig}'s
 * explicit {@code @ComponentScan} under the {@code phase1-mysql} profile.
 * {@code DevicesModuleIsolationTest} pins that.
 *
 * <p>Layout follows the design's seam: {@code zkteco} translates the vendor
 * protocol into {@link com.workin.devices.DeviceAttendanceEvent}s and knows
 * nothing about employees; {@code ingest} stores them idempotently and
 * resolves identities; {@code registry} owns the serial-to-tenant binding
 * that is the only source of tenant for anything a device sends;
 * {@code api} is the authenticated tenant surface for claiming and viewing.
 * Design: {@code docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md}.
 */
package com.workin.devices;
