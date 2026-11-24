package tn.finrisk.transactionservice.web.soap;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.SoapFaultException;
import tn.finrisk.transactionservice.config.WebServiceConfig;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.service.TransactionService;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;

/**
 * Faade SOAP/XML des transactions (exigence M2 : "transaction SOAP persistee en base").
 *
 * Elle n'a AUCUNE logique propre : elle convertit le XML en TransactionCreateRequest
 * et delegue a TransactionService, exactement comme le fait le TransactionController REST.
 * Une logique, deux protocoles.
 */
@Endpoint
public class TransactionSoapEndpoint {

    private static final String LOCAL_PART = "createTransactionRequest";

    private final TransactionService transactionService;

    public TransactionSoapEndpoint(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PayloadRoot(namespace = WebServiceConfig.NAMESPACE, localPart = LOCAL_PART)
    @ResponsePayload
    public CreateTransactionResponse createTransaction(@RequestPayload CreateTransactionRequest request) {
        TransactionCreateRequest dto;
        try {
            dto = new TransactionCreateRequest(
                    request.getAccountId(),
                    request.getMerchantId(),
                    request.getAmount(),
                    request.getCurrency(),
                    TransactionType.valueOf(request.getTransactionType()),
                    request.getCountry(),
                    channelOf(request.getChannel()),
                    request.getIpAddress(),
                    request.getDeviceId(),
                    request.getBeneficiaryName(),
                    // Pas de beneficiaire interne par SOAP : paiement marchand,
                    // le debit du compte source suffit a la regulation.
                    null,
                    // RIB optionnel : memes regles de validation/resolution
                    // que la facade REST (TransactionService.create).
                    request.getBeneficiaryRIB());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new SoapFaultException("Valeur enumeree inconnue dans la requete : " + ex.getMessage());
        }

        TransactionResponse created;
        try {
            created = transactionService.create(dto);
        } catch (RuntimeException ex) {
            // Le detail reste dans les logs serveur : le client SOAP recoit un fault generique.
            throw new SoapFaultException("Transaction refusee : " + ex.getMessage());
        }

        CreateTransactionResponse response = new CreateTransactionResponse();
        response.setTransactionId(created.transactionId());
        response.setStatus(created.status().name());
        return response;
    }

    private TransactionChannel channelOf(String channel) {
        return (channel == null || channel.isBlank()) ? null : TransactionChannel.valueOf(channel);
    }
}
