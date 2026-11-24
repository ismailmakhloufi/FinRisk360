import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Écran d'accès SENTINEL — POST /api/auth/login via le gateway. */
@Component({
  selector: 'app-login',
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  submit(username: string, password: string): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(null);

    this.auth.login(username.trim(), password).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Identifiants refusés');
        this.loading.set(false);
      },
    });
  }
}
