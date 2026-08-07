package com.workin.backend.organization;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Physical site (V29): GPS pair + radius drive the future geofencing
 * slice; qr_code/expires_at are issued by the deferred QR flow and
 * deliberately have no mutator here -- CRUD can never set them.
 */
@Entity
@Table(name = "branches")
public class Branch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	@Column
	private String address;

	@Column
	private BigDecimal latitude;

	@Column
	private BigDecimal longitude;

	@Column(name = "radius_meters", nullable = false)
	private int radiusMeters = 200;

	@Column(name = "qr_code")
	private String qrCode;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Branch() {
	}

	public Branch(Long companyId) {
		this.companyId = companyId;
	}

	public void apply(UpsertBranchRequest request) {
		this.name = request.name();
		this.address = request.address();
		this.latitude = request.latitude();
		this.longitude = request.longitude();
		this.radiusMeters = request.radiusMeters() != null ? request.radiusMeters() : 200;
		this.active = request.isActive() == null || request.isActive();
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

	public int getRadiusMeters() {
		return radiusMeters;
	}

	public String getQrCode() {
		return qrCode;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public boolean isActive() {
		return active;
	}

}
