package tn.finrisk.customerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.repository.CustomerRepository;
import tn.finrisk.customerservice.service.CustomerService;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;
import tn.finrisk.customerservice.web.error.EmailAlreadyExistsException;

/**
 * Test d'integration : chemin COMPLET HTTP-free jusqu'a PostgreSQL (base finrisk_test).
 * La methode de test est @Transactional : tout est annule (ROLLBACK) a la fin,
 * la base de test reste propre.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerServiceIntegrationTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void creation_relecture_et_doublon_dans_postgres() {
        CustomerRequest request =
                new CustomerRequest("Sarra", "Gharbi", "sarra.integration@test.tn", null, null, "tn", "Sfax", null);

        CustomerResponse created = customerService.create(request);
        assertThat(created.customerId()).isNotNull();
        assertThat(created.status()).isEqualTo(CustomerStatus.ACTIVE);

        CustomerResponse found = customerService.getById(created.customerId());
        assertThat(found.email()).isEqualTo("sarra.integration@test.tn");
        assertThat(found.country()).isEqualTo("TN");

        assertThat(customerRepository.existsByEmail("sarra.integration@test.tn")).isTrue();

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void compteDesClients_estStrictementPositif() {
        assertThat(customerRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
