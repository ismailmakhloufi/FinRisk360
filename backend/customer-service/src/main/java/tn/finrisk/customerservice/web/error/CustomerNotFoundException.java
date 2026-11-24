package tn.finrisk.customerservice.web.error;

/** Le client demande un customer_id qui n'existe pas -> HTTP 404. */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(Long customerId) {
        super("Client introuvable : id = " + customerId);
    }
}
