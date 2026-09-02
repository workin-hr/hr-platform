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
 *
 * <h2>Layers</h2>
 * <p>The same three the rest of this codebase uses, and for the same reason
 * -- a rule is testable and reusable only where it does not depend on how the
 * request arrived:
 * <ul>
 * <li><b>Controller</b> ({@code ZkTecoAdmsController},
 * {@code DeviceManagementController}) -- HTTP only: where a parameter comes
 * from, how the body is obtained, which status code an outcome becomes, and
 * what the JSON looks like. No decisions.</li>
 * <li><b>Service</b> ({@code ZkTecoAdmsService},
 * {@code DeviceManagementService}, {@code DevicePunchIngestionService}) --
 * every rule: serial validation, the trust model, record caps, what a tenant
 * may claim, how a PIN resolves. These are what a reader should open to learn
 * how the feature behaves.</li>
 * <li><b>Store</b> ({@code AttendanceDeviceStore}, {@code DevicePunchStore},
 * {@code EmployeeDeviceIdentityStore}, {@code UnclaimedDeviceSightingStore},
 * {@code DeviceOperationLogStore}) -- SQL over {@code legacyDataSource}, with
 * the tenant predicate on every query.</li>
 * </ul>
 * <p>Beside them sit pure, dependency-free translators -- the parser, the
 * handshake renderer, the operation-log filter, {@code DeviceInput} and
 * {@code QueryParameters} -- which is what lets the protocol's sharpest edges
 * be unit-tested without a container.
 *
 * <p>Design: {@code docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md}.
 */
package com.workin.devices;
