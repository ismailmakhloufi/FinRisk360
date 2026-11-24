import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { Assessment, Factor } from '../../api/evaluations.service';

/** Une ligne du waterfall : contribution réelle d'une règle. */
interface LabRow {
  factor: Factor;
  contribution: number;
  points: number;
  pct: number;
}

/** Résultat du « leave-one-out » : score si cette règle n'avait pas joué. */
interface LooRow {
  ruleCode: string;
  without: number;
  delta: number;
}

/**
 * SCORE LAB — preuve du chiffre (miroir exact du ScoringEngine Java) :
 *
 *   score = min( Σ(weight × facteur) × 100 × (1.20 si ≥3 règles déclenchées), 100)
 *
 * Trois regards, du plus simple au plus fort :
 *   1. la FORMULE  — d'où sort le nombre ;
 *   2. le WATERFALL — chaque règle, ses points ;
 *   3. le LEAVE-ONE-OUT — « sans RULE-002 le score serait 66 (−15) » :
 *      l'impact marginal de chaque indice, le vrai « plus fort que le modèle ».
 */
@Component({
  selector: 'app-score-lab',
  imports: [DecimalPipe],
  templateUrl: './score-lab.html',
  styleUrl: './score-lab.scss',
})
export class ScoreLab {
  /** Évaluation complète (score persisté + facteurs persistés). */
  readonly assessment = input.required<Assessment>();

  /** Contribution réelle = weight × facteur (0 | 0.5 | 1). */
  private readonly all = computed<LabRow[]>(() => {
    const rows = (this.assessment().factors ?? []).map((factor) => {
      const contribution = factor.weight * factor.factorScore;
      return {
        factor,
        contribution,
        points: contribution * 100,
        pct: 0,
      };
    });
    const max = Math.max(...rows.map((r) => r.contribution), 0.0001);
    rows.forEach((r) => (r.pct = (r.contribution / max) * 100));
    return rows.sort((a, b) => b.contribution - a.contribution);
  });

  /** Règles qui ont réellement apporté des points. */
  readonly triggered = computed(() => this.all().filter((r) => r.contribution > 0));

  /** Règles neutralisées / non jouées : visibles, jamais silencieuses. */
  readonly neutralized = computed(() => this.all().filter((r) => r.contribution <= 0));

  readonly sum = computed(() => this.all().reduce((acc, r) => acc + r.contribution, 0));
  readonly triggeredCount = computed(() => this.triggered().length);
  readonly totalCount = computed(() => this.all().length);

  /** BONUS de cumul ×1.20 dès 3 règles déclenchées (identique au backend). */
  readonly bonusActive = computed(() => this.triggeredCount() >= 3);

  /** Score recalculé par la formule — doit égaler riskScore. */
  readonly formulaScore = computed(() => {
    const raw = this.sum() * 100 * (this.bonusActive() ? 1.2 : 1);
    return Math.min(raw, 100);
  });

  /** Écart (normalement 0) entre formule et score persisté : honnêteté affichée. */
  readonly gap = computed(() => this.formulaScore() - this.assessment().riskScore);

  /** LEAVE-ONE-OUT : le score sans chaque règle déclenchée. */
  readonly loo = computed<LooRow[]>(() => {
    const sum = this.sum();
    const count = this.triggeredCount();
    const bonusAfter = (removed: boolean) => (count - (removed ? 1 : 0) >= 3 ? 1.2 : 1);
    return this.triggered().map((row) => {
      const without = Math.min((sum - row.contribution) * 100 * bonusAfter(true), 100);
      const delta = this.formulaScore() - without;
      return { ruleCode: row.factor.ruleCode, without, delta };
    });
  });

  /** La règle qui pèse le plus : le cœur du dossier. */
  readonly dominant = computed(() => this.triggered()[0] ?? null);

  /** Math.abs exposé au template (Angular n'expose pas Math). */
  abs(v: number): number {
    return Math.abs(v);
  }
}
