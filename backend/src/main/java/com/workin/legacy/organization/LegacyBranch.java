package com.workin.legacy.organization;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.TenantFilter;

/**
 * The legacy {@code branches} row (Wave 12.3, PR 12.3a; item 12 row 3):
 * {@code id}, {@code company_id}, {@code name}, {@code address}, {@code
 * latitude}/{@code longitude}, {@code radius_meters}, {@code qr_code},
 * {@code expires_at}, {@code is_active}, plus the one audit column
 * Java never writes (D-3) -- unlike {@code exception_types}, {@code
 * created_at} has no {@code ON UPDATE}, so there is no {@code
 * updated_at} column at all (verified against {@code
 * mysql_workin.schema.sql}'s {@code CREATE TABLE branches} block, not
 * assumed from Wave 12.1's shape). Direct {@code company_id} -- P-1a
 * tenancy, the same {@code @Filter} shape every prior Wave 12.x entity
 * carries.
 */
@Entity
@Table(name = "branches")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class LegacyBranch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "address", length = 500)
	private String address;

	@Column(name = "latitude", precision = 10, scale = 7)
	private BigDecimal latitude;

	@Column(name = "longitude", precision = 10, scale = 7)
	private BigDecimal longitude;

	/** {@code int(10) UNSIGNED NOT NULL DEFAULT 200} -- production carries real {@code 0} rows (D-059); never validated away. */
	@Column(name = "radius_meters", nullable = false)
	private Integer radiusMeters;

	@Column(name = "qr_code", length = 100)
	private String qrCode;

	/** {@code datetime}, not {@code timestamp} -- application-set only, legacy never defaults or maintains this column. */
	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "is_active", nullable = false)
	private Integer isActive;

	/** Database-maintained (D-3) -- never written by Java. No {@code updated_at} counterpart on this table. */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected LegacyBranch() {
	}

	public LegacyBranch(
			Long companyId, String name, String address, BigDecimal latitude, BigDecimal longitude,
			Integer radiusMeters) {
		this.companyId = companyId;
		this.name = name;
		this.address = address;
		this.latitude = latitude;
		this.longitude = longitude;
		this.radiusMeters = radiusMeters == null ? 200 : radiusMeters;
		this.isActive = LegacyValues.fromBoolean(true);
	}

	public Long getId() {
		return id;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

	public Integer getRadiusMeters() {
		return radiusMeters;
	}

	public String getQrCode() {
		return qrCode;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public boolean active() {
		return LegacyValues.toBoolean(isActive);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public void setLatitude(BigDecimal latitude) {
		this.latitude = latitude;
	}

	public void setLongitude(BigDecimal longitude) {
		this.longitude = longitude;
	}

	public void setRadiusMeters(Integer radiusMeters) {
		this.radiusMeters = radiusMeters;
	}

	public void setActive(boolean active) {
		this.isActive = LegacyValues.fromBoolean(active);
	}

	public void setQrCode(String qrCode) {
		this.qrCode = qrCode;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

}
