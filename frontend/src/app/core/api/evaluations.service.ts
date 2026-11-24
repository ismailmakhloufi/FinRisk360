import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

/** Miroir de FactorResponse : POURQUOI le score est ce qu'il est. */
export interface Factor {
  ruleId: number;
  ruleCode: string;
  ruleName: string;
  factorType: string;
  factorScore: number;
  weight: number;
  reason: string;
}

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Decision = 'APPROVED' | 'MONITORING' | 'PENDING_REVIEW' | 'HOLD';

/** Miroir de AssessmentResponse (risk-service M3). Score sur 0–100. */
export interface Assessment {
  assessmentId: number;
  transactionId: number;
  riskScore: number;
  riskLevel: RiskLevel;
  decision: Decision;
  assessedAt: string;
  engineVersion: string;
  factors: Factor[];
}

/** POST /api/evaluations {transactionId} (écriture : ANALYST/ADMIN). */
@Injectable({ providedIn: 'root' })
export class EvaluationsService {
  private readonly http = inject(HttpClient);

  evaluate(transactionId: number) {
    return this.http.post<Assessment>('/api/evaluations', { transactionId });
  }

  /** GET /api/evaluations/{id} — score + facteurs d'une alerte déjà évaluée. */
  get(assessmentId: number) {
    return this.http.get<Assessment>(`/api/evaluations/${assessmentId}`);
  }
}
