package com.kerosene.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeIdempotencyEntity;

@Repository
public interface KfeIdempotencyRepository extends JpaRepository<KfeIdempotencyEntity, com.kerosene.kfe.model.KfeIdempotencyId> {
}
