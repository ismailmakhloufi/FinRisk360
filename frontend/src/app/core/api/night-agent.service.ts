import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

/** Miroir de NightAgentDecision — ce que l'agent a fait pendant la nuit. */
export interface NightDecision {
  decisionId: number;
  transactionId: number;
  assessmentId: number | null;
  alertId: number | null;
  amount: number;
  riskScore: number | null;
  riskLevel: string | null;
  engineDecision: string | null;
  aiVerdict: string | null;
  aiConfidence: number | null;
  agentAction: 'VALIDATED' | 'LEFT_PENDING';
  agentReason: string;
  runAt: string;
}

/** Miroir de NightAgentService.Status. */
export interface NightStatus {
  enabled: boolean;
  autoValidationEnabled: boolean;
  startHour: number;
  endHour: number;
  inWindowNow: boolean;
  lastRunAt: string | null;
}

/** Miroir de NightAgentService.RunSummary. */
export interface NightRunSummary {
  scanned: number;
  validated: number;
  deferred: number;
  skipped: number;
  runAt: string;
}

/**
 * Agent nocturne (M4) : rapport du matin (GET), etat de la fenetre (GET),
 * declencheur manuel (POST — ignore la fenetre, action explicite de
 * l'analyste).
 */
@Injectable({ providedIn: 'root' })
export class NightAgentApi {
  private readonly http = inject(HttpClient);

  decisions(index: number, size = 25) {
    const params = new HttpParams().set('page', index).set('size', size);
    return this.http.get<Page<NightDecision>>('/api/night-agent/decisions', { params });
  }

  status() {
    return this.http.get<NightStatus>('/api/night-agent/status');
  }

  run() {
    return this.http.post<NightRunSummary>('/api/night-agent/run', null);
  }
}
