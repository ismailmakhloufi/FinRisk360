package tn.finrisk.transactionservice.web.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/** Reponse SOAP : l'identifiant cree et son statut (PENDING par defaut). */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "createTransactionResponseType", propOrder = {
        "transactionId",
        "status"
})
@XmlRootElement(name = "createTransactionResponse")
public class CreateTransactionResponse {

    private Long transactionId;
    private String status;

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
