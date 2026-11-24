package tn.finrisk.transactionservice.web.mapper;

import org.springframework.stereotype.Component;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.web.dto.MerchantCreateRequest;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;

/** Traduction entite <-> DTO des marchands. */
@Component
public class MerchantMapper {

    public Merchant toEntity(MerchantCreateRequest request) {
        Merchant merchant = new Merchant();
        merchant.setMerchantName(request.merchantName().trim());
        merchant.setMerchantCategory(request.merchantCategory());
        if (request.country() != null && !request.country().isBlank()) {
            merchant.setCountry(request.country().toUpperCase());
        }
        merchant.setCity(request.city());
        return merchant;
    }

    public MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                merchant.getMerchantCategory(),
                merchant.getCountry(),
                merchant.getCity(),
                merchant.getStatus(),
                merchant.getCreatedAt());
    }
}
