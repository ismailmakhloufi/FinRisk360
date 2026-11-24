package tn.finrisk.customerservice.web.mapper;

import org.springframework.stereotype.Component;
import tn.finrisk.customerservice.domain.Customer;
import tn.finrisk.customerservice.domain.enums.CustomerType;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;

/**
 * Traduction entite <-> DTO. Une ligne de code = un champ copie :
 * c'est ici, et uniquement ici, qu'on decide ce qui sort vers le client.
 */
@Component
public class CustomerMapper {

    /** JSON recu -> entite (pret pour INSERT). */
    public Customer toEntity(CustomerRequest request) {
        Customer customer = new Customer();
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setCountry(request.country() == null ? null : request.country().toUpperCase());
        customer.setCity(request.city());
        customer.setCustomerType(request.customerType() == null ? CustomerType.RETAIL : request.customerType());
        // customerId, registrationDate et status : cote serveur uniquement
        return customer;
    }

    /** Met a jour une entite existante (PUT /api/customers/{id}). */
    public void updateEntity(CustomerRequest request, Customer customer) {
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setCountry(request.country() == null ? null : request.country().toUpperCase());
        customer.setCity(request.city());
        if (request.customerType() != null) {
            customer.setCustomerType(request.customerType());
        }
    }

    /** Entite -> JSON envoye au client. */
    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getCustomerId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getDateOfBirth(),
                customer.getCountry(),
                customer.getCity(),
                customer.getCustomerType(),
                customer.getStatus(),
                customer.getRegistrationDate());
    }
}
