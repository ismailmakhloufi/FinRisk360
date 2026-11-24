import { TestBed } from '@angular/core/testing';
import { ScoreLab } from './score-lab';
import { Assessment, Factor } from '../../api/evaluations.service';

/** Facteur de test : poids × facteur = contribution (identique au backend). */
function f(ruleId: number, code: string, weight: number, factorScore: number): Factor {
  return {
    ruleId,
    ruleCode: code,
    ruleName: `Regle ${code}`,
    factorType: 'AMOUNT',
    factorScore,
    weight,
    reason: `test ${code}`,
  };
}

function assessmentOf(riskScore: number, factors: Factor[]): Assessment {
  return {
    assessmentId: 1,
    transactionId: 1,
    riskScore,
    riskLevel: 'HIGH',
    decision: 'PENDING_REVIEW',
    assessedAt: '2026-10-07T10:00:00Z',
    engineVersion: 'RULES-V1.0',
    factors,
  };
}

describe('ScoreLab', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ScoreLab] }).compileComponents();
  });

  function create(asm: Assessment): ScoreLab {
    const fixture = TestBed.createComponent(ScoreLab);
    fixture.componentRef.setInput('assessment', asm);
    return fixture.componentInstance;
  }

  it('recalcule exactement la formule du ScoringEngine : somme x 100 x 1.20', () => {
    // 0.25 + 0.15 + 0.20 = 0.60, 3 regles declenchees -> bonus
    const lab = create(
      assessmentOf(72, [
        f(1, 'RULE-001', 0.25, 1),
        f(2, 'RULE-002', 0.15, 1),
        f(3, 'RULE-003', 0.2, 1),
        f(7, 'RULE-007', 0.1, 0), // neutralisee : 0 point
      ]),
    );
    expect(lab.sum()).toBeCloseTo(0.6, 6);
    expect(lab.triggeredCount()).toBe(3);
    expect(lab.bonusActive()).toBe(true);
    expect(lab.formulaScore()).toBeCloseTo(72, 6); // 0.60 x 100 x 1.20
    expect(lab.gap()).toBeCloseTo(0, 6); // identique au score persiste
  });

  it('sans bonus au-dessous de 3 regles : somme x 100 seul', () => {
    const lab = create(
      assessmentOf(40, [f(1, 'RULE-001', 0.25, 1), f(2, 'RULE-002', 0.15, 1)]),
    );
    expect(lab.bonusActive()).toBe(false);
    expect(lab.formulaScore()).toBeCloseTo(40, 6); // 0.40 x 100
  });

  it('plafonne le score a 100 (bonus inclus)', () => {
    const lab = create(
      assessmentOf(100, [
        f(1, 'RULE-001', 0.4, 1),
        f(2, 'RULE-002', 0.3, 1),
        f(3, 'RULE-003', 0.3, 1),
      ]),
    );
    expect(lab.formulaScore()).toBe(100); // 1.00 x 100 x 1.20 = 120 -> 100
  });

  it('leave-one-out : sans RULE-001 le bonus tombe aussi (3 -> 2 regles)', () => {
    const lab = create(
      assessmentOf(72, [
        f(1, 'RULE-001', 0.25, 1),
        f(2, 'RULE-002', 0.15, 1),
        f(3, 'RULE-003', 0.2, 1),
      ]),
    );
    expect(lab.formulaScore()).toBeCloseTo(72, 6); // 0.60 x 100 x 1.20
    const loo = lab.loo();
    expect(loo.length).toBe(3);
    // sans RULE-001 : plus que 2 declenchees -> pas de bonus -> 0.35 x 100
    const without001 = loo.find((r) => r.ruleCode === 'RULE-001')!;
    expect(without001.without).toBeCloseTo(35, 6);
    expect(without001.delta).toBeCloseTo(37, 6);
    // sans RULE-002 : idem, 0.45 x 100 sans bonus
    const without002 = loo.find((r) => r.ruleCode === 'RULE-002')!;
    expect(without002.without).toBeCloseTo(45, 6);
    expect(without002.delta).toBeCloseTo(27, 6);
  });

  it('leave-one-out retombe a 1.00 si la suppression fait passer sous le seuil de bonus', () => {
    // exactement 3 declenchees : en retirer une -> 2 -> plus de bonus
    const lab = create(
      assessmentOf(60, [
        f(1, 'RULE-001', 0.2, 1),
        f(2, 'RULE-002', 0.2, 1),
        f(3, 'RULE-003', 0.2, 1),
      ]),
    );
    expect(lab.formulaScore()).toBeCloseTo(72, 6); // 0.60 x 100 x 1.20
    const loo = lab.loo();
    // sans RULE-001 : 0.40 x 100 x 1.00 = 40 (plus de bonus)
    expect(loo[0].without).toBeCloseTo(40, 6);
    expect(loo[0].delta).toBeCloseTo(32, 6);
  });

  it('classe la regle dominante en premier (RULE-001 = 25 points)', () => {
    const lab = create(
      assessmentOf(66, [
        f(7, 'RULE-007', 0.1, 1),
        f(1, 'RULE-001', 0.25, 1),
        f(6, 'RULE-006', 0.05, 1),
      ]),
    );
    expect(lab.dominant()?.factor.ruleCode).toBe('RULE-001');
    expect(lab.triggered()[0].points).toBeCloseTo(25, 6);
    expect(lab.neutralized().length).toBe(0);
  });

  it('affiche les regles non jouees comme neutralisees (facteur 0)', () => {
    const lab = create(
      assessmentOf(25, [f(1, 'RULE-001', 0.25, 1), f(4, 'RULE-004', 0.1, 0)]),
    );
    expect(lab.neutralized().length).toBe(1);
    expect(lab.neutralized()[0].factor.ruleCode).toBe('RULE-004');
    expect(lab.triggered().length).toBe(1);
  });
});
