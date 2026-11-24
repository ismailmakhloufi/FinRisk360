import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, concatMap, from, map, toArray } from 'rxjs';

/** Miroir de CustomerResponse (customer-service M1). */
export interface Customer {
  customerId: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  dateOfBirth: string;
  country: string;
  city: string;
  customerType: 'RETAIL' | 'PRIVATE' | 'PREMIUM';
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  registrationDate: string;
}

/**
 * Miroir de DeleteSummary : { deleted } (5 services, meme DTO).
 * L'etape est renvoyee avec le compte pour pouvoir raconter la cascade.
 */
export interface DeleteResult {
  step: string;
  deleted: number;
}

/** Page Spring : { content, totalElements, totalPages, number, size, … } */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** GET /api/customers — lecture seule via le gateway (M0 : pas d'accès direct). */
@Injectable({ providedIn: 'root' })
export class CustomersService {
  private readonly http = inject(HttpClient);

  page(index: number, size = 20, sort = 'customerId') {
    let params = new HttpParams()
      .set('page', index)
      .set('size', size)
      .set('sort', sort);
    return this.http.get<Page<Customer>>('/api/customers', { params });
  }

  /** GET /api/customers/{id} — titulaire du compte (détail alerte). */
  getById(id: number) {
    return this.http.get<Customer>(`/api/customers/${id}`);
  }

  /**
   * Suppression DURE d'un dossier entier : alertes, evaluations,
   * transactions, TOUS les comptes puis le client lui-meme.
   *
   * M0 : aucun service ne touche la base d'un autre, donc la cascade est
   * orchestrée ICI, par l'appelant — la meme choregraphie que
   * bank-simulator/cascade_delete_customer. Les alertes emportent cases
   * et commentaires (AlertService.deleteByCustomer).
   *
   * Sequentiel (concatMap) et non parallel : chaque etape depend de la
   * precedente, des suppressions simultanees echoueraient sur les FK.
   */
  deleteCascade(customerId: number): Observable<DeleteResult[]> {
    const steps: { label: string; url: string; byCustomer: boolean }[] = [
      { label: 'alertes', url: '/api/alerts', byCustomer: true },
      { label: 'evaluations', url: '/api/evaluations', byCustomer: true },
      { label: 'transactions', url: '/api/transactions', byCustomer: true },
      { label: 'comptes', url: '/api/accounts', byCustomer: true },
      { label: 'client', url: `/api/customers/${customerId}`, byCustomer: false },
    ];

    return from(steps).pipe(
      concatMap((s) =>
        this.http
          .delete<{ deleted: number }>(s.url, {
            params: s.byCustomer ? { customerId: String(customerId) } : undefined,
          })
          .pipe(map((res) => ({ step: s.label, deleted: res.deleted }))),
      ),
      toArray(),
    );
  }
}
