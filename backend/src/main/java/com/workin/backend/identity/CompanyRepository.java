package com.workin.backend.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	boolean existsByPhone(String phone);

}
