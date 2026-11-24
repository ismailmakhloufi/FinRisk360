package tn.finrisk.transactionservice.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.repository.MerchantRepository;
import tn.finrisk.transactionservice.web.dto.MerchantCreateRequest;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;
import tn.finrisk.transactionservice.web.error.MerchantAlreadyExistsException;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.mapper.MerchantMapper;

/** Logique metier des marchands (table propriaire de ce service). */
@Service
@Transactional(readOnly = true)
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;

    public MerchantService(MerchantRepository merchantRepository, MerchantMapper merchantMapper) {
        this.merchantRepository = merchantRepository;
        this.merchantMapper = merchantMapper;
    }

    public List<MerchantResponse> getAll() {
        return merchantRepository.findAll().stream()
                .map(merchantMapper::toResponse)
                .toList();
    }

    public MerchantResponse getById(Long id) {
        return merchantRepository.findById(id)
                .map(merchantMapper::toResponse)
                .orElseThrow(() -> new MerchantNotFoundException(id));
    }

    @Transactional
    public MerchantResponse create(MerchantCreateRequest request) {
        if (merchantRepository.existsByMerchantNameIgnoreCase(request.merchantName().trim())) {
            throw new MerchantAlreadyExistsException(request.merchantName().trim());
        }
        return merchantMapper.toResponse(merchantRepository.save(merchantMapper.toEntity(request)));
    }

    /**
     * DELETE /api/merchants/{id} : suppression dure du marchand.
     * Les transactions qui le referencent passent en merchant_id NULL
     * (fk_tx_merchant = ON DELETE SET NULL) : l'historique est conserve.
     */
    @Transactional
    public void delete(Long id) {
        if (!merchantRepository.existsById(id)) {
            throw new MerchantNotFoundException(id);
        }
        merchantRepository.deleteById(id);
    }
}
