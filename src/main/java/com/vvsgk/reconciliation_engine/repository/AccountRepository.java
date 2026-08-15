package com.vvsgk.reconciliation_engine.repository;
import com.vvsgk.reconciliation_engine.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccountRepository extends JpaRepository<Account, String> { }
