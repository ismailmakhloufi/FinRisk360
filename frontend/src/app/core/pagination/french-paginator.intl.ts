import { Provider } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';

/**
 * Libellés français du paginator Material — « Items per page: » et
 * « 1–8 of 8 » sont en anglais par défaut et cassent l'immersion SENTINEL.
 * Fourni UNE fois via app.config.ts : toutes les pages (Clients, Comptes,
 * Transactions, Alertes, Cas, Audit) héritent du même rendu.
 */
export function frenchPaginatorIntl(): MatPaginatorIntl {
  const intl = new MatPaginatorIntl();
  intl.itemsPerPageLabel = 'éléments par page';
  intl.firstPageLabel = 'Première page';
  intl.previousPageLabel = 'Page précédente';
  intl.nextPageLabel = 'Page suivante';
  intl.lastPageLabel = 'Dernière page';
  intl.getRangeLabel = (page: number, pageSize: number, length: number) => {
    if (length === 0 || pageSize === 0) {
      return `0 sur 0`;
    }
    const start = page * pageSize + 1;
    const end = Math.min((page + 1) * pageSize, length);
    return `${start}–${end} sur ${length}`;
  };
  return intl;
}

export const provideFrenchPaginator: Provider = {
  provide: MatPaginatorIntl,
  useFactory: frenchPaginatorIntl,
};
