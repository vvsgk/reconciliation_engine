package com.vvsgk.reconciliation_engine.repository;
import com.vvsgk.reconciliation_engine.entity.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditRecordRepository extends JpaRepository<AuditRecord, String> { }
