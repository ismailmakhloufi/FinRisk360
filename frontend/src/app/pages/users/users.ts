import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { AdminUser, PasswordReset, UsersService } from '../../core/api/users.service';
import { AuthService, UserProfile } from '../../core/auth/auth.service';
import { FrPipe, frLabel } from '../../core/pipes/fr';

const ROLES: UserProfile['role'][] = ['ADMIN', 'MANAGER', 'ANALYST', 'BANK_SIMULATOR'];

@Component({
  selector: 'app-users',
  imports: [DatePipe, FrPipe],
  templateUrl: './users.html',
  styleUrl: './users.scss',
})
export class Users implements OnInit {
  private readonly api = inject(UsersService);
  readonly auth = inject(AuthService);

  readonly roles = ROLES;
  readonly rows = signal<AdminUser[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly flash = signal<string | null>(null);
  readonly busy = signal(false);
  /** Désactivation = action destructive : exige un 2e clic (CONFIRMER). */
  readonly pendingToggle = signal<number | null>(null);
  /** Réinitialisation de mot de passe = aussi un 2e clic. */
  readonly pendingReset = signal<number | null>(null);
  /** Dernier mot de passe provisoire rendu — affiché une fois, jamais rechargé. */
  readonly resetResult = signal<PasswordReset | null>(null);
  readonly copied = signal(false);

  ngOnInit(): void {
    this.load();
  }

  /** Création — POST /api/users (409 si username déjà pris). */
  create(username: string, email: string, password: string, role: string): void {
    if (!username.trim() || !password || !role) {
      this.error.set('username, password (8+ car.) et rôle sont obligatoires.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .create({ username: username.trim(), email: email.trim(), password, role: role as UserProfile['role'] })
      .subscribe({
        next: (created) => {
          this.busy.set(false);
          this.load();
          this.flash.set(`utilisateur #${created.id} ${created.username} créé (${frLabel(created.role)})`);
          setTimeout(() => this.flash.set(null), 3000);
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — création refusée`);
        },
      });
  }

  /** 1er clic = arme la confirmation, 2e clic = exécute (3,5 s pour valider). */
  toggleActive(user: AdminUser): void {
    if (this.pendingToggle() === user.id) {
      this.pendingToggle.set(null);
      this.patch(user, { active: !user.active }, `${user.username} → ${!user.active ? 'ACTIF' : 'DÉSACTIVÉ'}`);
      return;
    }
    this.pendingToggle.set(user.id);
    setTimeout(() => {
      if (this.pendingToggle() === user.id) this.pendingToggle.set(null);
    }, 3500);
  }

  /**
   * Réinitialisation (mot de passe oublié, ex. l'analyste) : le serveur
   * GÉNÈRE le mot de passe et le rend en clair une seule fois. On l'affiche
   * pour le transmettre à l'utilisateur — il n'est ni stocké (BCrypt) ni
   * journalisé (audit n'en garde qu'un résumé).
   */
  resetPassword(user: AdminUser): void {
    if (this.pendingReset() === user.id) {
      this.pendingReset.set(null);
      this.error.set(null);
      this.api.resetPassword(user.id).subscribe({
        next: (res) => {
          this.resetResult.set(res);
          this.flash.set(`mot de passe de ${res.username} réinitialisé`);
          setTimeout(() => this.flash.set(null), 3000);
        },
        error: (err) =>
          this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — réinitialisation refusée`),
      });
      return;
    }
    this.pendingReset.set(user.id);
    setTimeout(() => {
      if (this.pendingReset() === user.id) this.pendingReset.set(null);
    }, 3500);
  }

  async copyReset(): Promise<void> {
    const pwd = this.resetResult()?.password;
    if (!pwd) return;
    try {
      await navigator.clipboard.writeText(pwd);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    } catch {
      this.error.set("Copie impossible (contexte non sécurisé) — recopiez le mot de passe à la main.");
    }
  }

  dismissReset(): void {
    this.resetResult.set(null);
    this.copied.set(false);
  }

  changeRole(user: AdminUser, role: string): void {
      this.patch(user, { role: role as UserProfile['role'] }, `${user.username} → ${frLabel(role)}`);
  }

  /** L'utilisateur ne peut ni se désactiver ni se dégrader lui-même. */
  isSelf(user: AdminUser): boolean {
    return user.id === this.auth.user()?.id;
  }

  private patch(user: AdminUser, data: { active?: boolean; role?: UserProfile['role'] }, label: string): void {
    this.error.set(null);
    this.api.update(user.id, data).subscribe({
      next: (updated) => {
        this.rows.set(this.rows().map((u) => (u.id === updated.id ? updated : u)));
        this.flash.set(label);
        setTimeout(() => this.flash.set(null), 3000);
      },
      error: (err) => this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — modification refusée`),
    });
  }

  private load(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (users) => {
        this.rows.set(users);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — service injoignable`);
        this.loading.set(false);
      },
    });
  }
}
