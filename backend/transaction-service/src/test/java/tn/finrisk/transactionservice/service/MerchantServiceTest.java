package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.domain.enums.MerchantStatus;
import tn.finrisk.transactionservice.repository.MerchantRepository;
import tn.finrisk.transactionservice.web.dto.MerchantCreateRequest;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;
import tn.finrisk.transactionservice.web.error.MerchantAlreadyExistsException;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.mapper.MerchantMapper;

/** Tests UNITAIRES des regles marchands (unicite du nom, 404). */
@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;

    private MerchantService merchantService;

    @BeforeEach
    void setUp() {
        merchantService = new MerchantService(merchantRepository, new MerchantMapper());
    }

    @Test
    void create_donneesValides_trimEtPaysEnMajuscules() {
        when(merchantRepository.existsByMerchantNameIgnoreCase("Tunis Market")).thenReturn(false);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant saved = invocation.getArgument(0);
            saved.setMerchantId(5L);
            return saved;
        });

        MerchantResponse response = merchantService.create(
                new MerchantCreateRequest("  Tunis Market ", "RETAIL", "tn", "Tunis"));

        assertThat(response.merchantId()).isEqualTo(5L);
        assertThat(response.merchantName()).isEqualTo("Tunis Market");
        assertThat(response.country()).isEqualTo("TN");
        assertThat(response.status()).isEqualTo(MerchantStatus.ACTIVE);
    }

    @Test
    void create_nomDejaUtilise_leveConflitEtNeSauvegardePas() {
        when(merchantRepository.existsByMerchantNameIgnoreCase("Tunis Market")).thenReturn(true);

        assertThatThrownBy(() -> merchantService.create(
                new MerchantCreateRequest("Tunis Market", "RETAIL", "TN", "Tunis")))
                .isInstanceOf(MerchantAlreadyExistsException.class)
                .hasMessageContaining("Tunis Market");

        verify(merchantRepository, never()).save(any());
    }

    @Test
    void getById_inconnu_leveNotFound() {
        when(merchantRepository.findById(42L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> merchantService.getById(42L))
                .isInstanceOf(MerchantNotFoundException.class)
                .hasMessageContaining("42");
    }
}
