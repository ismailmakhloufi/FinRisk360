import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService, UserProfile } from './auth.service';

/**
 * Garde de rôle (RBAC front) : la matrice du gateway reste le vrai verrou,
 * ce garde évite juste d'afficher une page à qui ne peut pas l'utiliser.
 * Ex. : { path: 'utilisateurs', canActivate: [roleGuard], data: { roles: ['ADMIN'] } }
 */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const roles = (route.data?.['roles'] ?? []) as UserProfile['role'][];
  if (roles.length === 0 || auth.hasRole(...roles)) {
    return true;
  }
  return router.parseUrl('/dashboard');
};
