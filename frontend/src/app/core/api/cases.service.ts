import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

export type CaseStatus = 'OPEN' | 'UNDER_REVIEW' | 'CONFIRMED' | 'FALSE_POSITIVE' | 'CLOSED';
export type CasePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** Miroir de CaseResponse (alert-case-service M5). */
export interface CaseFile {
  caseId: number;
  alertId: number;
  assignedTo: number | null;
  status: CaseStatus;
  priority: CasePriority;
  investigationResult: string | null;
  openedAt: string;
  closedAt: string | null;
}

/** Cases : ouverture depuis une alerte + cycle de vie. */
@Injectable({ providedIn: 'root' })
export class CasesService {
  private readonly http = inject(HttpClient);

  page(index: number, size = 20, status?: CaseStatus) {
    let params = new HttpParams().set('page', index).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<Page<CaseFile>>('/api/cases', { params });
  }

  /** GET /api/cases/{id} */
  get(id: number) {
    return this.http.get<CaseFile>(`/api/cases/${id}`);
  }

  /** GET /api/alerts/{alertId}/case — dossier rattaché à l'alerte (404 si aucun). */
  getByAlertId(alertId: number) {
    return this.http.get<CaseFile>(`/api/alerts/${alertId}/case`);
  }

  /** POST /api/alerts/{alertId}/cases — défaut côté serveur : MEDIUM. */
  open(alertId: number, priority?: CasePriority) {
    return this.http.post<CaseFile>(`/api/alerts/${alertId}/cases`, priority ? { priority } : {});
  }

  /** PATCH /api/cases/{id}/status — transitions contrôlées backend. */
  changeStatus(id: number, status: CaseStatus, investigationResult?: string) {
    return this.http.patch<CaseFile>(`/api/cases/${id}/status`, {
      status,
      investigationResult: investigationResult || null,
    });
  }
}
