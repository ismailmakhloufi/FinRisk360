package tn.finrisk.authservice.service;

import java.security.SecureRandom;

/**
 * Mot de passe PROVISOIRE genere quand l'ADMIN reinitialise un compte dont
 * l'utilisateur a oublie l'acces : l'analyste n'a plus son mot de passe,
 * l'admin le lui rend.
 *
 * 14 caracteres, au moins une minuscule / une majuscule / un chiffre / un
 * symbole (politique de UserCreateRequest : 8 a 72 caracteres), alphabet sans
 * les glyphes ambigus (0/O, 1/l/I) pour que l'admin le recopie sans erreur.
 *
 * Le clair n'existe QU'AU MOMENT de la reinitialisation : il est rendu une
 * seule fois a l'appelant, jamais stocke (hash BCrypt) ni journalise
 * (audit_logs ne recoit qu'un resume, jamais le mot de passe).
 */
final class PasswordGenerator {

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "-_.!@#$%+";
    private static final String ALL = LOWER + UPPER + DIGIT + SYMBOL;
    private static final int LENGTH = 14;

    private PasswordGenerator() {
    }

    static String generate() {
        return generate(new SecureRandom());
    }

    /** Surcharge testable : le generateur est fourni pour pouvoir le figer. */
    static String generate(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH);
        // Une garantie par classe, puis remplissage au hasard...
        sb.append(pick(random, LOWER));
        sb.append(pick(random, UPPER));
        sb.append(pick(random, DIGIT));
        sb.append(pick(random, SYMBOL));
        while (sb.length() < LENGTH) {
            sb.append(pick(random, ALL));
        }
        // ...puis melange : sans lui les 4 garanties resteraient toujours
        // aux 4 premieres positions (position = indice connu d'un attaquant).
        for (int i = sb.length() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = sb.charAt(i);
            sb.setCharAt(i, sb.charAt(j));
            sb.setCharAt(j, tmp);
        }
        return sb.toString();
    }

    private static char pick(SecureRandom random, String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
