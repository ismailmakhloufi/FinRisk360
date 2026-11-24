import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Pose `Authorization: Bearer <token>` sur TOUTES les requêtes /api.
 * Un 401 hors login = session expirée → déconnexion automatique.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token;

  const authReq =
    token && req.url.startsWith('/api')
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authReq).pipe(
    catchError((err: unknown) => {
      const isLoginCall = req.url.includes('/api/auth/login');
      if (err instanceof HttpErrorResponse && err.status === 401 && !isLoginCall) {
        auth.logout();
      }
      return throwError(() => err);
    }),
  );
};
