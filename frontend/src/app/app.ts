import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Racine : le layout réel (shell / login) est choisi par les routes. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
