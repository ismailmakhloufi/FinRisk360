import { DecimalPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { FrPipe } from '../../pipes/fr';

/**
 * Jauge SVG (composant signature SENTINEL) : arc de 0 à 100
 * qui prend la couleur du niveau de risque, animé en stroke-dashoffset.
 */
@Component({
  selector: 'app-risk-gauge',
  imports: [DecimalPipe, FrPipe],
  templateUrl: './risk-gauge.html',
  styleUrl: './risk-gauge.scss',
})
export class RiskGauge {
  readonly score = input.required<number>();
  readonly level = input<string>('LOW');

  /** Longueur de l'arc de demi-cercle : π × r (r = 80). */
  private static readonly ARC_LENGTH = Math.PI * 80;

  get offset(): number {
    const clamped = Math.min(Math.max(this.score(), 0), 100);
    return RiskGauge.ARC_LENGTH * (1 - clamped / 100);
  }
}
