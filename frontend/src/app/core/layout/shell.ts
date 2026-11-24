import { Component, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../auth/auth.service';
import { FrPipe } from '../pipes/fr';

interface NavItem {
  icon: string;
  label: string;
  path: string;
}

/**
 * Shell SENTINEL : barre latérale à rôle + barre système + routage enfant.
 * Le menu Administration n'appartient qu'à ADMIN (filtre par rôle du JWT).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatIconModule, MatButtonModule, FrPipe],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  readonly auth = inject(AuthService);

  readonly nav: NavItem[] = [
    { icon: 'space_dashboard', label: 'Tableau de bord', path: '/dashboard' },
    { icon: 'group', label: 'Clients', path: '/clients' },
    { icon: 'account_balance', label: 'Comptes', path: '/comptes' },
    { icon: 'list_alt', label: 'Journal', path: '/transactions' },
    { icon: 'notifications_active', label: 'Alertes', path: '/alertes' },
    { icon: 'folder_open', label: 'Dossiers', path: '/cases' },
    { icon: 'bedtime', label: 'Rapport de nuit', path: '/rapport-nuit' },
    { icon: 'auto_awesome', label: 'Assistant IA', path: '/ia' },
  ];

  readonly adminNav: NavItem[] = [
    { icon: 'manage_accounts', label: 'Utilisateurs', path: '/utilisateurs' },
    { icon: 'receipt_long', label: 'Audit', path: '/audit' },
  ];

  /** Initiales de l'utilisateur (avatar SENTINEL). */
  initials(): string {
    const name = this.auth.user()?.username ?? '??';
    return name.slice(0, 2).toUpperCase();
  }
}
