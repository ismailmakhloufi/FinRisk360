import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

/** Miroir d'Insight — le signal détecté par l'IA avec sa preuve. */
export interface Insight {
  code: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  message: string;
  evidence: string;
}

/** Miroir de AnalysisResponse : score moteur + avis IA CÔTE À CÔTÉ. */
export interface Analysis {
  alertId: number;
  assessmentId: number;
  riskScore: number;
  riskLevel: string;
  decision: string;
  verdict: 'NORMAL' | 'INCERTAIN' | 'FRAUDE_SUSPECTE';
  confidence: number;
  insights: Insight[];
  explanation: string;
  analyzedAt: string;
}

/** Miroir de ChatResponse — mode = comment la réponse a été produite. */
export interface ChatAnswer {
  alertId: number;
  intent: string;
  mode: 'DETERMINISTE' | 'LLM';
  answer: string;
}

/** Façade M6 : analyse à la demande + chat sur UNE alerte. */
@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);

  analyze(alertId: number) {
    return this.http.post<Analysis>(`/api/ai/alerts/${alertId}/analyze`, null);
  }

  chat(alertId: number, message: string) {
    return this.http.post<ChatAnswer>('/api/ai/chat', { alertId, message });
  }
}
