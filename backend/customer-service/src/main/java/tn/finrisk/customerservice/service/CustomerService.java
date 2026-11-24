package tn.finrisk.customerservice.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.customerservice.domain.Customer;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.repository.CustomerRepository;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;
import tn.finrisk.customerservice.web.error.CustomerNotFoundException;
import tn.finrisk.customerservice.web.error.EmailAlreadyExistsException;
import tn.finrisk.customerservice.web.mapper.CustomerMapper;

/**
 * Logique metier du domaine client.
 * Regles appliquees ici (jamais dans le controleur ni dans le repository) :
 *  - un email ne peut etre porte que par un seul client
 *  - un client introuvable -> CustomerNotFoundException (HTTP 404)
 *  - suppression douce par defaut (PATCH status) ; suppression DURE (DELETE)
 *    uniquement apres que l'appelant a purge ses propres tables
 *    (cascade : alerts -> evaluations -> transactions -> accounts -> ICI)
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final JdbcTemplate jdbc;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper,
                           JdbcTemplate jdbc) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.jdbc = jdbc;
    }

    /** GET /api/customers?page=0&size=20 */
    public Page<CustomerResponse> getAll(Pageable pageable) {
        return customerRepository.findAll(pageable).map(customerMapper::toResponse);
    }

    /** GET /api/customers/{id} */
    public CustomerResponse getById(Long id) {
        return customerRepository.findById(id)
                .map(customerMapper::toResponse)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    /** POST /api/customers */
    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        assertEmailIsFree(request.email());
        Customer customer = customerRepository.save(customerMapper.toEntity(request));
        return customerMapper.toResponse(customer);
    }

    /** PUT /api/customers/{id} */
    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        boolean emailChanged = request.email() != null
                && !request.email().equalsIgnoreCase(customer.getEmail());
        if (emailChanged) {
            assertEmailIsFree(request.email());
        }

        customerMapper.updateEntity(request, customer);
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    /** PATCH /api/customers/{id}/status */
    @Transactional
    public CustomerResponse changeStatus(Long id, CustomerStatus status) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customer.setStatus(status);
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    /**
     * DELETE /api/customers/{id} : suppression DURE du client.
     * Ordonnancement impose par les FK (l'APPELANT purge dans l'ordre) :
     *   alerts -> evaluations -> transactions -> accounts -> ICI customers.
     * risk_profiles part ici en CASCADE (fk_rp_customer) ; 404 si inconnu.
     */
    @Transactional
    public int delete(Long id) {
        if (!customerRepository.existsById(id)) {
            throw new CustomerNotFoundException(id);
        }
        return jdbc.update("DELETE FROM customers WHERE customer_id = ?", id);
    }

    /** Utilise par les autres services de la meme app (comptes, profil). */
    public Customer getReference(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    /** La base a UNIQUE (email) : on signale proprement plutot qu'une erreur 500. */
    private void assertEmailIsFree(String email) {
        if (email != null && customerRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
    }
}
