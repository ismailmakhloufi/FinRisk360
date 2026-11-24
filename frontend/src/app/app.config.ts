import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { provideFrenchPaginator } from './core/pagination/french-paginator.intl';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Toutes les requetes /api passent par le proxy  gateway :8080,
    // l'interceptor y pose le Bearer token et g�re les 401 (M5).
    provideHttpClient(withInterceptors([authInterceptor])),
    // Paginator Material en francais (« elements par page », « 1-8 sur 8 »)
    // au lieu des libelles anglais par defaut.
    provideFrenchPaginator,
  ],
};
