import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Decision = 'APPROVED' | 'MONITORING' | 'PENDING_REVIEW' | 'HOLD';

/** Comptage par jour UTC (J-6 .. J) — zero-fill côté backend. */
export interface DayCount {
  day: string;
  count: number;
}

export interface TopBeneficiary {
  name: string;
  count: number;
  totalAmount: number;
}

/** Miroir de AlertStatsResponse (GET /api/alerts/stats). */
export interface AlertStats {
  totalAlerts: number;
  byStatus: Record<string, number>;
  bySeverity: Record<string, number>;
  totalCases: number;
  openCases: number;
  openGroups: number;
  groupCumulAmount: number;
  absorbedAmount: number;
  /** Alertes HIGH/CRITICAL encore actives (non terminales) — bannière priorité. */
  activeHighCritical: number;
  /** Alertes clôturées : RESOLVED + FALSE_POSITIVE — taux de décision. */
  closedAlerts: number;
  /** Durée moyenne de résolution en minutes — null si aucune clôture. */
  avgResolutionMinutes: number | null;
  activity7d: DayCount[];
}

/** Miroir de TransactionStatsResponse (GET /api/transactions/stats?days=N). */
export interface TransactionStats {
  totalTransactions: number;
  topBeneficiaries: TopBeneficiary[];
  /** Zéro-fill sur N jours (7 par défaut, 30 avec le toggle). */
  activity: DayCount[];
  /** Volume cumulé sur la fenêtre d'activité. */
  volume: number;
  /** % de variation vs la fenêtre précédente — null si base vide. */
  trendPct: number | null;
  byType: Record<string, number>;
  byChannel: Record<string, number>;
}

/** Miroir de ProfileDigest : profil de comportement glissant 90 j. */
export interface ProfileDigest {
  customerId: number;
  profileRiskLevel: RiskLevel;
  avgAmount: number | null;
  p95Amount: number | null;
  usualCountry: string | null;
  usualTransactionHour: number | null;
  statsWindowDays: number;
}

/** Miroir de EvaluationStatsResponse (GET /api/evaluations/stats). */
export interface EvaluationStats {
  totalAssessments: number;
  byDecision: Record<string, number>;
  byRiskLevel: Record<string, number>;
  avgScore: number;
  maxScore: number;
  watchlist: ProfileDigest[];
}

/**
 * Agregats SQL du dashboard — agrégats complets (pas d'échantillon paginé)
 * pour que les compteurs de la page d'accueil soient toujours exacts.
 */
@Injectable({ providedIn: 'root' })
export class StatsService {
  private readonly http = inject(HttpClient);

  /** GET /api/alerts/stats — répartition alertes + activité 7 j. */
  alertStats() {
    return this.http.get<AlertStats>('/api/alerts/stats');
  }

  /** GET /api/transactions/stats?days=N — top + activité N jours + type/canal. */
  transactionStats(days = 7) {
    return this.http.get<TransactionStats>(`/api/transactions/stats?days=${days}`);
  }

  /** GET /api/evaluations/stats — décisions, scores, watchlist profils. */
  evaluationStats() {
    return this.http.get<EvaluationStats>('/api/evaluations/stats');
  }
}
