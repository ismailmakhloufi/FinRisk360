import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

/** Miroir de AccountResponse (account-service M2). */
export interface Account {
  accountId: number;
  customerId: number;
  accountNumber: string;
  accountType: 'CHECKING' | 'SAVINGS' | 'CARD';
  currency: string;
  balance: number;
  openingDate: string;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
}

/** GET /api/accounts + PATCH /{id}/status (écriture : MANAGER/ADMIN au gateway). */
@Injectable({ providedIn: 'root' })
export class AccountsService {
  private readonly http = inject(HttpClient);

  page(index: number, size = 20, customerId?: number) {
    let params = new HttpParams()
      .set('page', index)
      .set('size', size)
      .set('sort', 'accountId');
    if (customerId != null) {
      params = params.set('customerId', customerId);
    }
    return this.http.get<Page<Account>>('/api/accounts', { params });
  }

  /** GET /api/accounts/{id} — compte porteur d'une transaction (détail alerte). */
  getById(id: number) {
    return this.http.get<Account>(`/api/accounts/${id}`);
  }

  changeStatus(id: number, status: Account['status']) {
    return this.http.patch<Account>(`/api/accounts/${id}/status`, null, {
      params: { status },
    });
  }
}
