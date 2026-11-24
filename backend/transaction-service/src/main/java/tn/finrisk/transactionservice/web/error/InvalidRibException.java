package tn.finrisk.transactionservice.web.error;

/**
 * RIB du beneficiaire malforme : longueur differente de 20 chiffres, cle de
 * controle (modulo 97) incorrecte, ou RIB designant le compte SOURCE du
 * virement. Traduit en 400 : un virement n'est jamais cree sur un RIB
 * saisi n'importe comment (il partirait nulle part).
 */
public class InvalidRibException extends RuntimeException {

    public InvalidRibException(String message) {
        super(message);
    }
}
