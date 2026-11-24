import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { CustomersService, Customer, Page } from '../../core/api/customers.service';
import { AuthService } from '../../core/auth/auth.service';
import { FrPipe } from '../../core/pipes/fr';

const COLUMNS = [
  'customerId', 'name', 'email', 'location', 'customerType',
  'status', 'registrationDate', 'actions',
];

@Component({
  selector: 'app-customers',
  imports: [MatTableModule, MatPaginatorModule, DatePipe, FrPipe],
  templateUrl: './customers.html',
  styleUrl: './customers.scss',
})
export class Customers implements OnInit {
  private readonly api = inject(CustomersService);
  readonly auth = inject(AuthService);

  readonly columns = COLUMNS;
  readonly rows = signal<Customer[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly pageSize = 20;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly flash = signal<string | null>(null);
  readonly busy = signal(false);
  /**
   * Suppression de dossier = 5 appels (alertes, evaluations, transactions,
   * comptes, client). La matrice du gateway n'autorise les 5 QUE si on est
   * ADMIN : MANAGER echouerait sur les alertes/evaluations, ANALYST sur
   * les comptes — inutile d'afficher un bouton qui casserait a mi-chemin.
   */
  readonly canDelete = this.auth.hasRole('ADMIN');
  /** Suppression = action destructive : exige un 2e clic (CONFIRMER). */
  readonly pendingDelete = signal<number | null>(null);

  ngOnInit(): void {
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  /**
   * 1er clic = arme la confirmation, 2e clic = execute (4 s pour valider).
   * Efface TOUT le dossier du client, pas seulement la ligne affichee.
   */
  deleteCustomer(row: Customer): void {
    if (this.pendingDelete() === row.customerId) {
      this.pendingDelete.set(null);
      this.runDelete(row);
      return;
    }
    this.pendingDelete.set(row.customerId);
    setTimeout(() => {
      if (this.pendingDelete() === row.customerId) this.pendingDelete.set(null);
    }, 4000);
  }

  private runDelete(row: Customer): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.deleteCascade(row.customerId).subscribe({
      next: (results) => {
        this.busy.set(false);
        const detail = results.map((r) => `${r.deleted} ${r.step}`).join(', ');
        this.flash.set(`Client #${row.customerId} supprimé : ${detail}`);
        setTimeout(() => this.flash.set(null), 6000);
        this.load(this.page());
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(
          err?.error?.message ?? `Erreur ${err?.status ?? '?'} — suppression interrompue`,
        );
      },
    });
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.page(index, this.pageSize).subscribe({
      next: (data: Page<Customer>) => {
        this.rows.set(data.content);
        this.total.set(data.totalElements);
        this.page.set(data.number);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — service injoignable`);
        this.loading.set(false);
      },
    });
  }
}
