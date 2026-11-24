package tn.finrisk.customerservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tn.finrisk.customerservice.domain.Customer;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.domain.enums.CustomerType;
import tn.finrisk.customerservice.repository.CustomerRepository;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;
import tn.finrisk.customerservice.web.error.CustomerNotFoundException;
import tn.finrisk.customerservice.web.error.EmailAlreadyExistsException;
import tn.finrisk.customerservice.web.mapper.CustomerMapper;

/**
 * Test unitaire de la logique metier : aucun framework HTTP, aucune base.
 * Le repository est un FAUX (Mockito) : on ne teste que les decisions du service.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, new CustomerMapper(),
                mock(JdbcTemplate.class));
    }

    @Test
    void create_emailDejaUtilise_leveEmailAlreadyExistsException() {
        CustomerRequest request = request("amine@mail.tn");
        when(customerRepository.existsByEmail("amine@mail.tn")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("amine@mail.tn");

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void create_donneesValides_appliqueLesValeursParDefaut() {
        CustomerRequest request = request("amine@mail.tn");
        request = new CustomerRequest(request.firstName(), request.lastName(), request.email(),
                request.phone(), request.dateOfBirth(), "tn", request.city(), request.customerType());
        when(customerRepository.existsByEmail("amine@mail.tn")).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            customer.setCustomerId(42L);
            return customer;
        });

        CustomerResponse response = customerService.create(request);

        assertThat(response.customerId()).isEqualTo(42L);
        assertThat(response.country()).isEqualTo("TN");
        assertThat(response.customerType()).isEqualTo(CustomerType.RETAIL);
        assertThat(response.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void getById_identifiantInconnu_leveCustomerNotFoundException() {
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(999L))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void getById_identifiantExistant_retourneLeDto() {
        Customer customer = new Customer();
        customer.setCustomerId(1L);
        customer.setFirstName("Amine");
        customer.setLastName("Ben Ali");
        customer.setEmail("amine@mail.tn");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getById(1L);

        assertThat(response.customerId()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Amine");
        assertThat(response.email()).isEqualTo("amine@mail.tn");
    }

    @Test
    void changeStatus_sansParametre_retourneLeNouveauStatut() {
        Customer customer = new Customer();
        customer.setCustomerId(7L);
        customer.setFirstName("Amine");
        customer.setLastName("Ben Ali");
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse response = customerService.changeStatus(7L, CustomerStatus.SUSPENDED);

        assertThat(response.status()).isEqualTo(CustomerStatus.SUSPENDED);
    }

    private CustomerRequest request(String email) {
        return new CustomerRequest("Amine", "Ben Ali", email, null, null, "TN", "Tunis", null);
    }
}
