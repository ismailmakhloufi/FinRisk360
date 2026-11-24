package tn.finrisk.authservice.web.error;

/** Token valide mais droits insuffisants ou compte desactive -> 403.
 *  Separation 401/403 = critere du jalon M5 : 401 « pas (de token) »,
 *  403 « token present mais pas le droit ». */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
