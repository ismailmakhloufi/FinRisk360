import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Pas de session → /login. Protège tout le shell M7. */
export const authGuard: CanMatchFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isLoggedIn() ? true : router.createUrlTree(['/login']);
};

/** Déjà connecté → /dashboard (évite de revoir l'écran de login). */
export const guestGuard: CanMatchFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isLoggedIn() ? router.createUrlTree(['/dashboard']) : true;
};
