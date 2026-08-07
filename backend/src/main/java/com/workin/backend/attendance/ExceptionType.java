package com.workin.backend.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Company-defined attendance exception category (V21). Minimal
 * lookup for the punches-XOR-exception convention -- update/delete
 * deferred per the slice spec.
 */
@Entity
@Table(name = "exception_types")
public class ExceptionType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(nullable = false)
	private String name;

	protected ExceptionType() {
	}

	public ExceptionType(Long companyId, String name) {
		this.companyId = companyId;
		this.name = name;
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

}
