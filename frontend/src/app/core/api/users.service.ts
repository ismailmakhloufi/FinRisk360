import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { UserProfile } from '../auth/auth.service';

/** Miroir de UserResponse (jamais de passwordHash côté backend). */
export interface AdminUser {
  id: number;
  username: string;
  email: string;
  role: UserProfile['role'];
  active: boolean;
  createdAt: string;
}

export type CreateUserData = {
  username: string;
  email: string;
  password: string;
  role: UserProfile['role'];
};

export type UpdateUserData = {
  role?: UserProfile['role'];
  active?: boolean;
  password?: string;
};

/** Miroir de PasswordResetResponse — clair rendu UNE SEULE FOIS. */
export interface PasswordReset {
  id: number;
  username: string;
  password: string;
}

/** GET/POST/PATCH /api/users — ADMIN exclusif au gateway. */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  list() {
    return this.http.get<AdminUser[]>('/api/users');
  }

  create(data: CreateUserData) {
    return this.http.post<AdminUser>('/api/users', data);
  }

  update(id: number, patch: UpdateUserData) {
    return this.http.patch<AdminUser>(`/api/users/${id}`, patch);
  }

  /** POST /api/users/{id}/password — serveur génère, renvoie le clair 1 fois. */
  resetPassword(id: number) {
    return this.http.post<PasswordReset>(`/api/users/${id}/password`, {});
  }
}
