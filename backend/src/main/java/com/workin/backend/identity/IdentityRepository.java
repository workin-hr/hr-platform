package com.workin.backend.identity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityRepository extends JpaRepository<Identity, Long> {

	Optional<Identity> findByPhone(String phone);

	boolean existsByPhone(String phone);

}
