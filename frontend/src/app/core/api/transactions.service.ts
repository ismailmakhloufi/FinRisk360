import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Page } from './customers.service';

export type TransactionType = 'PAYMENT' | 'TRANSFER' | 'WITHDRAWAL' | 'DEPOSIT';
export type TransactionChannel = 'MOBILE' | 'WEB' | 'ATM' | 'POS' | 'API';
export type TransactionStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'HELD' | 'CANCELLED';

/** Miroir de TransactionResponse (transaction-service M4). */
export interface Transaction {
  transactionId: number;
  accountId: number;
  merchantId: number | null;
  amount: number;
  currency: string;
  transactionType: TransactionType;
  country: string;
  transactionDate: string;
  channel: TransactionChannel;
  ipAddress: string | null;
  deviceId: string | null;
  beneficiaryName: string | null;
  /** Compte interne credite a l'acceptation (null = destinataire externe). */
  beneficiaryAccountId: number | null;
  /** RIB TN canonique valide et resolu a la creation (null = pas de RIB). */
  beneficiaryRIB: string | null;
  status: TransactionStatus;
  createdAt: string;
}

/** Charge utile de POST /api/transactions (écriture : ANALYST/ADMIN). */
export interface TransactionCreate {
  accountId: number;
  amount: number;
  currency: string;
  transactionType: TransactionType;
  country: string;
  channel?: TransactionChannel;
  beneficiaryName?: string;
  beneficiaryAccountId?: number;
  /** RIB TN du beneficiaire : valide (mod 97) et resolu par le backend. */
  beneficiaryRIB?: string;
}

/** Filtres de recherche (GET /api/transactions) — tous optionnels. */
export interface TransactionFilter {
  accountId?: number;
  status?: TransactionStatus;
  /** Date ISO yyyy-MM-dd, début inclusif (UTC). */
  since?: string;
  /** Date ISO yyyy-MM-dd, jour inclusif (UTC). */
  until?: string;
  /** Sous-chaine sur le bénéficiaire, insensible à la casse. */
  beneficiary?: string;
  /** Client → tous ses comptes. */
  customerId?: number;
}

@Injectable({ providedIn: 'root' })
export class TransactionsService {
  private readonly http = inject(HttpClient);

  create(body: TransactionCreate) {
    return this.http.post<Transaction>('/api/transactions', body);
  }

  /**
   * GET /api/transactions — paginé + filtres combinés.
   * Tri : champ ASC, prefixe "-" pour DESC (ex. '-transactionDate').
   */
  page(index: number, size = 10, filters: TransactionFilter = {}, sort = 'transactionId') {
    let params = new HttpParams().set('page', index).set('size', size).set('sort', sort);
    if (filters.accountId != null) params = params.set('accountId', filters.accountId);
    if (filters.status) params = params.set('status', filters.status);
    if (filters.since) params = params.set('since', filters.since);
    if (filters.until) params = params.set('until', filters.until);
    if (filters.beneficiary) params = params.set('beneficiary', filters.beneficiary);
    if (filters.customerId != null) params = params.set('customerId', filters.customerId);
    return this.http.get<Page<Transaction>>('/api/transactions', { params });
  }

  /** GET /api/transactions/{id} — statut live (simulateur en attente). */
  getById(id: number) {
    return this.http.get<Transaction>(`/api/transactions/${id}`);
  }

  /** PATCH /api/transactions/{id}/status (écriture : ANALYST/ADMIN). */
  changeStatus(id: number, status: TransactionStatus) {
    return this.http.patch<Transaction>(`/api/transactions/${id}/status?status=${status}`, null);
  }
}
