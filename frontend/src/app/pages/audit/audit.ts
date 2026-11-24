import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { AuditEntry, AuditService } from '../../core/api/audit.service';
import { Page } from '../../core/api/customers.service';
import { FrPipe } from '../../core/pipes/fr';

@Component({
  selector: 'app-audit',
  imports: [MatTableModule, MatPaginatorModule, DatePipe, FrPipe],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
})
export class Audit implements OnInit {
  private readonly api = inject(AuditService);

  readonly rows = signal<AuditEntry[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.page(index, 25).subscribe({
      next: (data: Page<AuditEntry>) => {
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
