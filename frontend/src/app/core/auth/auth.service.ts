import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap } from 'rxjs';

/** Donnees de connexion utilisees par la console. */
export interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: 'ADMIN' | 'ANALYST' | 'MANAGER' | 'BANK_SIMULATOR';
  active: boolean;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserProfile;
}

/**
 * Session de l'utilisateur : login via le gateway, jeton conserve en memoire,
 * rôle exposé en signal (le shell et les gardes le lisent).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly session = signal<Pick<TokenResponse, 'accessToken' | 'user'> | null>(null);

  constructor() {
    try {
      localStorage.removeItem('finrisk.session');
    } catch {
      // Le stockage peut ?tre d?sactiv? par le navigateur.
    }
  }

  readonly isLoggedIn = computed(() => this.session() !== null);
  readonly user = computed(() => this.session()?.user ?? null);
  readonly role = computed(() => this.session()?.user.role ?? null);

  get token(): string | null {
    return this.session()?.accessToken ?? null;
  }

  /** POST /api/auth/login — route publique du gateway. */
  login(username: string, password: string) {
    return this.http
      .post<TokenResponse>('/api/auth/login', { username, password })
      .pipe(
        tap(({ accessToken, user }) => this.session.set({ accessToken, user })),
      );
  }

  logout(): void {
    this.session.set(null);
    this.router.navigate(['/login']);
  }

  /** Le rôle courant est-il dans la liste ? (matrice RBAC du front) */
  hasRole(...roles: UserProfile['role'][]): boolean {
    const current = this.role();
    return current !== null && roles.includes(current);
  }
}
