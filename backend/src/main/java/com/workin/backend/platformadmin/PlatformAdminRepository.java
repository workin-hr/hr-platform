package com.workin.backend.platformadmin;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

	Optional<PlatformAdmin> findByPhone(String phone);

	boolean existsByPhone(String phone);

}
