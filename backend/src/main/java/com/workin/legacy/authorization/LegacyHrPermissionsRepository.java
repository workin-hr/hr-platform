package com.workin.legacy.authorization;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads {@link LegacyHrPermissions} from the legacy MySQL contract. */
public interface LegacyHrPermissionsRepository extends JpaRepository<LegacyHrPermissions, Long> {

	Optional<LegacyHrPermissions> findByEmployeeId(Long employeeId);

}
