package com.workin.devices.agent;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.legacy.LegacyJdbcValues;

/** {@code device_agents}. The token hash is written here and compared here, and read nowhere else. */
@Component
public class DeviceAgentStore {

	private static final String COLUMNS = """
			id, company_id, name, token_hint, is_active, agent_version, last_seen_at, last_seen_ip,
			last_report, created_at""";

	private final JdbcTemplate jdbcTemplate;

	public DeviceAgentStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	public long create(long companyId, String name, String tokenSha256, String tokenHint, LocalDateTime now) {
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(now);
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO device_agents
					  (company_id, name, token_sha256, token_hint, is_active, created_at, updated_at)
					VALUES (?, ?, ?, ?, 1, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, companyId);
			statement.setString(2, name);
			statement.setString(3, tokenSha256);
			statement.setString(4, tokenHint);
			statement.setString(5, stamp);
			statement.setString(6, stamp);
			return statement;
		}, keys);
		return keys.getKey().longValue();
	}

	/** Active agents only: a deactivated agent's token authenticates nothing. */
	public Optional<DeviceAgent> findActiveByTokenSha256(String tokenSha256) {
		return first(jdbcTemplate.query(
				"SELECT " + COLUMNS + " FROM device_agents WHERE token_sha256 = ? AND is_active = 1",
				LegacyJdbcValues.rowMapper(), tokenSha256));
	}

	public Optional<DeviceAgent> find(long id) {
		return first(jdbcTemplate.query(
				"SELECT " + COLUMNS + " FROM device_agents WHERE id = ?", LegacyJdbcValues.rowMapper(), id));
	}

	/**
	 * Newest first; every company when {@code companyId} is null.
	 *
	 * <p>One page, always: this read had no {@code LIMIT} at all, so the
	 * administrator's page grew a row per agent ever issued with nothing to stop
	 * it. A caller wanting all of them asks for a page big enough and can tell
	 * from {@link #count(Long)} whether it got them.
	 */
	public List<DeviceAgent> list(Long companyId, int limit, long offset) {
		List<Map<String, Object>> rows = companyId == null
				? jdbcTemplate.query(
						"SELECT " + COLUMNS + " FROM device_agents ORDER BY id DESC LIMIT ? OFFSET ?",
						LegacyJdbcValues.rowMapper(), limit, offset)
				: jdbcTemplate.query(
						"SELECT " + COLUMNS + " FROM device_agents WHERE company_id = ?"
								+ " ORDER BY id DESC LIMIT ? OFFSET ?",
						LegacyJdbcValues.rowMapper(), companyId, limit, offset);
		return rows.stream().map(DeviceAgentStore::toAgent).toList();
	}

	/** How many agents that same filter matches, ignoring the page. */
	public int count(Long companyId) {
		Integer total = companyId == null
				? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_agents", Integer.class)
				: jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM device_agents WHERE company_id = ?", Integer.class, companyId);
		return total == null ? 0 : total;
	}

	public boolean setActive(long id, boolean active, LocalDateTime now) {
		return jdbcTemplate.update("UPDATE device_agents SET is_active = ?, updated_at = ? WHERE id = ?",
				active ? 1 : 0, DeviceAttendanceEvent.SQL_DATE_TIME.format(now), id) > 0;
	}

	public void recordContact(long id, String ip, LocalDateTime now) {
		jdbcTemplate.update("UPDATE device_agents SET last_seen_at = ?, last_seen_ip = ? WHERE id = ?",
				DeviceAttendanceEvent.SQL_DATE_TIME.format(now), ip, id);
	}

	public void recordHeartbeat(long id, String ip, String agentVersion, String report, LocalDateTime now) {
		jdbcTemplate.update("""
				UPDATE device_agents
				SET last_seen_at = ?, last_seen_ip = ?, agent_version = ?, last_report = ?
				WHERE id = ?""",
				DeviceAttendanceEvent.SQL_DATE_TIME.format(now), ip, agentVersion, report, id);
	}

	private static Optional<DeviceAgent> first(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(toAgent(rows.get(0)));
	}

	private static DeviceAgent toAgent(Map<String, Object> row) {
		return new DeviceAgent(
				Long.parseLong(String.valueOf(row.get("id"))),
				Long.parseLong(String.valueOf(row.get("company_id"))),
				text(row.get("name")), text(row.get("token_hint")),
				!"0".equals(String.valueOf(row.get("is_active"))),
				text(row.get("agent_version")), text(row.get("last_seen_at")), text(row.get("last_seen_ip")),
				text(row.get("last_report")), text(row.get("created_at")));
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
