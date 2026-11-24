package tn.finrisk.alertcaseservice.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** La machine a etats de case, testee PUR : ni base, ni HTTP. */
class CaseStatusTest {

    @Test
    void ouverteNePeutQuePasserEnRevue() {
        assertThat(CaseStatus.OPEN.canMoveTo(CaseStatus.UNDER_REVIEW)).isTrue();
        assertThat(CaseStatus.OPEN.canMoveTo(CaseStatus.CONFIRMED)).isFalse();
        assertThat(CaseStatus.OPEN.canMoveTo(CaseStatus.CLOSED)).isFalse();
        assertThat(CaseStatus.OPEN.canMoveTo(CaseStatus.FALSE_POSITIVE)).isFalse();
    }

    @Test
    void enRevueTroisIssuesPossibles() {
        assertThat(CaseStatus.UNDER_REVIEW.canMoveTo(CaseStatus.CONFIRMED)).isTrue();
        assertThat(CaseStatus.UNDER_REVIEW.canMoveTo(CaseStatus.FALSE_POSITIVE)).isTrue();
        assertThat(CaseStatus.UNDER_REVIEW.canMoveTo(CaseStatus.CLOSED)).isTrue();
        assertThat(CaseStatus.UNDER_REVIEW.canMoveTo(CaseStatus.OPEN)).isFalse();
    }

    @Test
    void fraudeConfirmeeSeClotureSeulement() {
        assertThat(CaseStatus.CONFIRMED.canMoveTo(CaseStatus.CLOSED)).isTrue();
        assertThat(CaseStatus.CONFIRMED.canMoveTo(CaseStatus.FALSE_POSITIVE)).isFalse();
        assertThat(CaseStatus.CONFIRMED.canMoveTo(CaseStatus.OPEN)).isFalse();
    }

    @Test
    void etatsTerminauxBloquentTout() {
        assertThat(CaseStatus.CLOSED.isTerminal()).isTrue();
        assertThat(CaseStatus.FALSE_POSITIVE.isTerminal()).isTrue();
        for (CaseStatus cible : CaseStatus.values()) {
            assertThat(CaseStatus.CLOSED.canMoveTo(cible)).isFalse();
            assertThat(CaseStatus.FALSE_POSITIVE.canMoveTo(cible)).isFalse();
        }
        assertThat(CaseStatus.CONFIRMED.isTerminal()).isFalse();
        assertThat(CaseStatus.UNDER_REVIEW.isTerminal()).isFalse();
    }
}
