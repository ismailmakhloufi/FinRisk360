package tn.finrisk.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Politique du mot de passe provisoire rendu a l'admin (probleme 6). */
class PasswordGeneratorTest {

    private static final String AMBIGU = "0O1lI";

    @Test
    void generate_14Caracteres_etJamaisDeGlyphesConfus() {
        for (int i = 0; i < 500; i++) {
            String p = PasswordGenerator.generate();
            assertThat(p).hasSize(14);
            for (char c : p.toCharArray()) {
                assertThat(AMBIGU).doesNotContain(String.valueOf(c));
            }
        }
    }

    @Test
    void generate_toujoursAuMoinsUneClasseDeCaracteres() {
        for (int i = 0; i < 500; i++) {
            String p = PasswordGenerator.generate();
            assertThat(p).as("minuscule manquante dans %s", p).matches(".*[a-z].*");
            assertThat(p).as("majuscule manquante dans %s", p).matches(".*[A-Z].*");
            assertThat(p).as("chiffre manquant dans %s", p).matches(".*[2-9].*");
            assertThat(p).as("symbole manquant dans %s", p).matches(".*[-_.!@#$%+].*");
        }
    }

    @Test
    void generate_deuxAppels_neRedonnentJamaisLeMemeMotDePasse() {
        Set<String> vus = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            vus.add(PasswordGenerator.generate());
        }
        assertThat(vus).hasSize(200);
    }

    @Test
    void generate_conformeALaPolitiqueDeCreation_8A72Caracteres() {
        for (int i = 0; i < 100; i++) {
            assertThat(PasswordGenerator.generate()).hasSizeBetween(8, 72);
        }
    }
}
