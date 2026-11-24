package tn.finrisk.accountservice.web.error;

/**
 * RIB malforme : longueur differente de 20 chiffres, ou cle de controle
 * (modulo 97) incorrecte. Traduit en 400 — un RIB saisi n'importe comment
 * ne doit JAMAIS passer pour un destinataire "externe" : le client se
 * tromperait d'une chiffre et son envoi partirait nulle part.
 */
public class InvalidRibException extends RuntimeException {

    public InvalidRibException(String message) {
        super(message);
    }
}
