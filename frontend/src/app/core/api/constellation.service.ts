import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

/**
 * Nœud du graphe d'investigation — miroir de ConstellationNode (backend).
 *
 * risk et investigationFocus sont deux notions DISTINCTES :
 *   - investigationFocus : le nœud est le CENTRE de l'investigation.
 *   - risk : le niveau que les données disponibles étayent.
 * Une transaction focale à 25/100 peut être le centre du graphe sans être HIGH.
 */
export interface ConstNode {
  id: string;
  type: 'ALERT' | 'CUSTOMER' | 'ACCOUNT' | 'TRANSACTION' | 'BENEFICIARY'
      | 'DEVICE' | 'IP' | 'COUNTRY' | 'GROUP';
  label: string;
  risk: 'HIGH' | 'MEDIUM' | 'LOW';
  investigationFocus: boolean;
  subtitle: string;
  meta: Record<string, unknown>;
}

/** Arête vérifiable — miroir de ConstellationEdge (backend). */
export interface ConstEdge {
  id: string;
  source: string;
  target: string;
  type: string;
  label: string;
  evidence: string;
  weight: number;
  proofTransactions?: readonly { transactionId: number; date: string | null }[];
}

/**
 * Narratif français (LLM ou repli déterministe) — miroir de
 * ConstellationNarrative (backend).
 *
 * dataQuality décrit la COMPLÉTUDE DES DONNÉES, pas le volume de texte rendu :
 *   COMPLETE  : scan complet, historique, client et comptes présents
 *   PARTIAL   : scan tronqué, historique vide, client ou comptes manquants
 *   DEGRADED  : transaction focale absente ou graphe réduit à l'alerte
 *
 * llmScore / llmScoreRationale : l'AVIS AUTONOME du LLM (0-100), fondé sur
 * les preuves des données — null en mode déterministe.
 */
export interface ConstNarrative {
  summary: string;
  hypotheses: string[];
  recommendedActions: string[];
  focusNodes: string[];
  dataQuality: 'COMPLETE' | 'PARTIAL' | 'DEGRADED';
  llmScore?: number | null;
  llmScoreRationale?: string | null;
  checks?: readonly {
    fact: string;
    hypothesis: string;
    alternative: string;
    verification: string;
    evidence: readonly { label: string; detail: string; edgeId: string | null }[];
  }[];
}

/** Facteur du score moteur (règle + points + POIDS) — panneau POURQUOI. */
export interface ScoreFactor {
  ruleCode: string | null;
  ruleName: string | null;
  points: number | null;
  weight: number | null;
  reason: string | null;
}

/**
 * Réponse complète de POST /api/ai/alerts/{id}/constellation.
 * truncated/hiddenSharedAccounts : le graphe est borné (lisibilité) et le
 * backend DÉCLARE ce qui a été écarté plutôt que de le cacher.
 * readingLine : phrase de lecture déterministe bâtie sur les arêtes réelles.
 */
export interface Constellation {
  alertId: number;
  alertCode: string;
  nodes: ConstNode[];
  edges: ConstEdge[];
  narrative: ConstNarrative;
  mode: 'LLM' | 'DETERMINISTE';
  truncated: boolean;
  hiddenSharedAccounts: number;
  scoreFactors: ScoreFactor[];
  readingLine: string | null;
  generatedAt: string;
}

/** Façade M6+ : constellation d'investigation d'une alerte. */
@Injectable({ providedIn: 'root' })
export class ConstellationService {
  private readonly http = inject(HttpClient);

  /** POST /api/ai/alerts/{alertId}/constellation — graphe + narratif. */
  build(alertId: number) {
    return this.http.post<Constellation>(`/api/ai/alerts/${alertId}/constellation`, null);
  }
}
