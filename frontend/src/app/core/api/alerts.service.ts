import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

export type AlertType = 'HIGH_AMOUNT' | 'VELOCITY' | 'GEOGRAPHIC' | 'BEHAVIOR' | 'DUPLICATE' | 'OTHER';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
/**
 * ABSORBED : alerte absorbee par son groupe d'agregation (2e+ alerte du
 * meme compte en 24h). Masquee de la file principale — le groupe est le
 * point d'entree — mais visible comme membre du groupe (audit).
 */
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'IN_PROGRESS' | 'RESOLVED' | 'FALSE_POSITIVE' | 'ABSORBED';

/**
 * Miroir de AlertResponse (alert-case-service M5).
 * groupId : groupe d'alertes apparentées (agregation), null si l'alerte
 *   n'a pas pu être rattachée (panne de transaction-service à la création).
 * amount  : montant copié de la transaction, pour afficher le cumul du groupe.
 */
export interface Alert {
  alertId: number;
  assessmentId: number;
  alertCode: string;
  alertType: AlertType;
  severity: Severity;
  status: AlertStatus;
  assignedTo: number | null;
  groupId: number | null;
  amount: number | null;
  createdAt: string;
  resolvedAt: string | null;
}

export type GroupScope = 'ACCOUNT' | 'CUSTOMER' | 'BENEFICIARY';
/**
 * REFUSED : groupe refuse par l'analyste — TOUTES les transactions du
 * groupe ont ete annulees + remboursees (fraude confirmee).
 */
export type GroupStatus = 'OPEN' | 'RESOLVED' | 'FALSE_POSITIVE' | 'REFUSED';

/** Miroir de AlertGroupResponse : un AGREGAT d'alertes apparentées. */
export interface AlertGroup {
  groupId: number;
  scopeType: GroupScope;
  scopeValue: string;
  customerId: number | null;
  windowStart: string;
  windowEnd: string;
  windowHours: number;
  /** Sévérité AGRÉGÉE : monte avec le nombre d'alertes membres. */
  severity: Severity;
  status: GroupStatus;
  relatedCount: number;
  totalAmount: number;
  createdAt: string;
  updatedAt: string;
  memberAlertIds: number[];
}

/** POST /api/alerts (severity absente → dérivée du verdict côté backend). */
@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly http = inject(HttpClient);

  create(assessmentId: number, alertType: AlertType) {
    return this.http.post<Alert>('/api/alerts', { assessmentId, alertType });
  }

  /** GET /api/alerts/{id} — cible d'un lien case → alerte (?alert=). */
  get(id: number) {
    return this.http.get<Alert>(`/api/alerts/${id}`);
  }

  page(index: number, size = 20, status?: AlertStatus) {
    let params = new HttpParams().set('page', index).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<Page<Alert>>('/api/alerts', { params });
  }

  /** PATCH /api/alerts/{id}/status (écriture : ANALYST/ADMIN). */
  changeStatus(id: number, status: AlertStatus) {
    return this.http.patch<Alert>(`/api/alerts/${id}/status`, { status });
  }
}

/**
 * Lecture + DECISION sur les GROUPES d'alertes apparentées (agregation M5).
 * Le groupe est créé automatiquement à chaque alerte (AlertGroupService.attach)
 * ; l'analyste peut TRANCHER dessus (decide) :
 *   ACCEPT — pattern légitime : alertes membres FALSE_POSITIVE ;
 *   REFUSE — fraude confirmée : transactions annulées + remboursées.
 */
@Injectable({ providedIn: 'root' })
export class AlertGroupsService {
  private readonly http = inject(HttpClient);

  /** GET /api/alert-groups — groupes encore ouverts. */
  list() {
    return this.http.get<AlertGroup[]>('/api/alert-groups');
  }

  /** GET /api/alert-groups/{id} — un groupe + ses alertes membres. */
  get(id: number) {
    return this.http.get<AlertGroup>(`/api/alert-groups/${id}`);
  }

  /**
   * GET /api/alert-groups/{id}/alerts — détail complet des alertes membres
   * (sévérité/statut/montant individuels intacts). Permet à l'analyste de
   * voir TOUTES les alertes du pattern avant de trancher ACCEPT/REFUSE.
   */
  members(id: number, page = 0, size = 50) {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Alert>>(`/api/alert-groups/${id}/alerts`, { params });
  }

  /**
   * PATCH /api/alert-groups/{id}/decision — décision de l'analyste.
   * REFUSE = annulation + remboursement de TOUTES les transactions du
   * groupe (rejouable côté backend si un échec partiel survient).
   */
  decide(id: number, decision: 'ACCEPT' | 'REFUSE', reason?: string) {
    return this.http.patch<AlertGroup>(`/api/alert-groups/${id}/decision`, {
      decision,
      reason: reason || null,
    });
  }
}
