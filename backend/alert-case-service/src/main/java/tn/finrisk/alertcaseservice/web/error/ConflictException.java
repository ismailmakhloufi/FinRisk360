package tn.finrisk.alertcaseservice.web.error;

/**
 * Conflit metier HTTP 409 :
 *  - transition hors graphe (AlertStatus.canMoveTo / CaseStatus.canMoveTo),
 *  - doublon (une alerte par evaluation, une case par alerte),
 *  - mutation sur un etat terminal (alerte resolue, case cloturee),
 *  - violation de contrainte SQL (unicite / FK) traduite par le handler.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
