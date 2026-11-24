import { Routes } from '@angular/router';
import { Login } from './pages/login/login';
import { Shell } from './core/layout/shell';
import { Customers } from './pages/customers/customers';
import { Accounts } from './pages/accounts/accounts';
import { Transactions } from './pages/transactions/transactions';
import { Alerts } from './pages/alerts/alerts';
import { Cases } from './pages/cases/cases';
import { Users } from './pages/users/users';
import { Audit } from './pages/audit/audit';
import { Dashboard } from './pages/dashboard/dashboard';
import { authGuard, guestGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

/**
 * M7 — 2 layouts :
 *   /login  → écran dédié (hors shell)
 *   /*      → shell SENTINEL, protégé par authGuard
 */
export const routes: Routes = [
  { path: 'login', component: Login, canMatch: [guestGuard] },
  {
    path: '',
    component: Shell,
    canMatch: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', component: Dashboard },
      { path: 'clients', component: Customers },
      { path: 'comptes', component: Accounts },
      { path: 'transactions', component: Transactions },
      { path: 'alertes', component: Alerts },
      { path: 'cases', component: Cases },
      { path: 'rapport-nuit', loadComponent: () => import('./pages/night-report/night-report').then(m => m.NightReport) },
      { path: 'ia', loadComponent: () => import('./pages/ia/ia').then(m => m.Ia) },
      { path: 'constellation/:alertId', loadComponent: () => import('./pages/constellation/constellation').then(m => m.ConstellationPage) },
      {
        path: 'utilisateurs',
        component: Users,
        canActivate: [roleGuard],
        data: { roles: ['ADMIN'] },
      },
      {
        path: 'audit',
        component: Audit,
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'MANAGER'] },
      },
      { path: '**', redirectTo: 'dashboard' },
    ],
  },
  { path: '**', redirectTo: '' },
];
