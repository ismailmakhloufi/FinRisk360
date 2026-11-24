import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

/** Miroir d'AuditResponse — la traçabilité exigée par M4. */
export interface AuditEntry {
  auditId: number;
  userId: number | null;
  action: string;
  entityType: string;
  entityId: number | null;
  oldValue: string | null;
  newValue: string | null;
  createdAt: string;
  ipAddress: string | null;
}

/** GET /api/audit — lecture : MANAGER/ADMIN au gateway. */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  page(index: number, size = 25) {
    const params = new HttpParams().set('page', index).set('size', size);
    return this.http.get<Page<AuditEntry>>('/api/audit', { params });
  }
}
