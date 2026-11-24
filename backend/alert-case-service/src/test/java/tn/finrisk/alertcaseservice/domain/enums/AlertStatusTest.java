package tn.finrisk.alertcaseservice.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** La machine a etats d'alerte, testee PUR : ni base, ni HTTP. */
class AlertStatusTest {

    @Test
    void ouverteNePeutQuePasseEnReconnaissance() {
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.ACKNOWLEDGED)).isTrue();
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.IN_PROGRESS)).isFalse();
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.RESOLVED)).isFalse();
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.FALSE_POSITIVE)).isFalse();
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.OPEN)).isFalse();
    }

    @Test
    void parcoursNormalVersResolution() {
        assertThat(AlertStatus.ACKNOWLEDGED.canMoveTo(AlertStatus.IN_PROGRESS)).isTrue();
        assertThat(AlertStatus.IN_PROGRESS.canMoveTo(AlertStatus.RESOLVED)).isTrue();
    }

    @Test
    void fausseAlerteDepuisReconnaissanceOuInvestigation() {
        assertThat(AlertStatus.ACKNOWLEDGED.canMoveTo(AlertStatus.FALSE_POSITIVE)).isTrue();
        assertThat(AlertStatus.IN_PROGRESS.canMoveTo(AlertStatus.FALSE_POSITIVE)).isTrue();
    }

    @Test
    void etatsTerminauxBloquentTout() {
        assertThat(AlertStatus.RESOLVED.isTerminal()).isTrue();
        assertThat(AlertStatus.FALSE_POSITIVE.isTerminal()).isTrue();
        assertThat(AlertStatus.ABSORBED.isTerminal()).isTrue();
        for (AlertStatus cible : AlertStatus.values()) {
            assertThat(AlertStatus.RESOLVED.canMoveTo(cible)).isFalse();
            assertThat(AlertStatus.FALSE_POSITIVE.canMoveTo(cible)).isFalse();
            assertThat(AlertStatus.ABSORBED.canMoveTo(cible)).isFalse();
        }
        assertThat(AlertStatus.OPEN.isTerminal()).isFalse();
        assertThat(AlertStatus.ACKNOWLEDGED.isTerminal()).isFalse();
        assertThat(AlertStatus.IN_PROGRESS.isTerminal()).isFalse();
    }

    @Test
    void absorbeeNePeutRienDevenir() {
        // ABSORBED = alerte masquee par son groupe : etat terminal, aucune
        // transition possible (ni retour a OPEN, ni cloture manuelle).
        for (AlertStatus cible : AlertStatus.values()) {
            assertThat(AlertStatus.ABSORBED.canMoveTo(cible)).isFalse();
        }
        assertThat(AlertStatus.OPEN.canMoveTo(AlertStatus.ABSORBED)).isFalse();
    }
}
