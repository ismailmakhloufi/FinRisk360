package tn.finrisk.transactionservice.web.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.math.BigDecimal;

/**
 * Charge utile SOAP : miroir XML de TransactionCreateRequest (REST).
 * Meme donnees, autre encodage : c'est la "2e faconde" d'appeler le meme service.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "createTransactionRequestType", propOrder = {
        "accountId",
        "merchantId",
        "amount",
        "currency",
        "transactionType",
        "country",
        "channel",
        "ipAddress",
        "deviceId",
        "beneficiaryName",
        "beneficiaryRIB"
})
@XmlRootElement(name = "createTransactionRequest")
public class CreateTransactionRequest {

    private Long accountId;
    private Long merchantId;
    private BigDecimal amount;
    private String currency;
    private String transactionType;
    private String country;
    private String channel;
    private String ipAddress;
    private String deviceId;
    private String beneficiaryName;
    private String beneficiaryRIB;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public void setBeneficiaryName(String beneficiaryName) {
        this.beneficiaryName = beneficiaryName;
    }

    public String getBeneficiaryRIB() {
        return beneficiaryRIB;
    }

    public void setBeneficiaryRIB(String beneficiaryRIB) {
        this.beneficiaryRIB = beneficiaryRIB;
    }
}
