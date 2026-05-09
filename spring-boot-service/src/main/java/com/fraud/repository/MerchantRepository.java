package com.fraud.repository;

import com.fraud.model.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, String> {
    List<Merchant> findByActiveTrue();
    List<Merchant> findByWebhookUrlIsNotNull();
}
