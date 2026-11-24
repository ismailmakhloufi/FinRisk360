import { DecimalPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { FrPipe } from '../../pipes/fr';

export type StepState = 'idle' | 'running' | 'done' | 'failed' | 'skipped';

/** Une étape de la chaîne : tx → evaluate → alert. */
export interface ChainStep {
  key: 'tx' | 'evaluate' | 'alert';
  label: string;
  endpoint: string;
  state: StepState;
  detail?: string;
}

/** Le verdict du Risk Engine à afficher sous la chaîne. */
export interface Verdict {
  score: number;
  riskLevel: string;
  decision: string;
  assessmentId: number;
}

/**
 * Composant signature SENTINEL : la chaîne vivante
 * tx ✓ → evaluate ✓ → alert ✓ (ou skip si verdict sain).
 */
@Component({
  selector: 'app-chain-stepper',
  imports: [DecimalPipe, FrPipe],
  templateUrl: './chain-stepper.html',
  styleUrl: './chain-stepper.scss',
})
export class ChainStepper {
  readonly steps = input.required<ChainStep[]>();
  readonly verdict = input<Verdict | null>(null);
}
