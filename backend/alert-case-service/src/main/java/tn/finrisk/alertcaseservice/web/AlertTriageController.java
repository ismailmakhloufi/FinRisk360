package tn.finrisk.alertcaseservice.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.alertcaseservice.service.AlertTriageService;
import tn.finrisk.alertcaseservice.service.AlertTriageService.Triage;

/** La route existante du gateway reserve les POST aux analystes et admins. */
@RestController
@RequestMapping("/api/alerts")
public class AlertTriageController {
    private final AlertTriageService triage;

    public AlertTriageController(AlertTriageService triage) {
        this.triage = triage;
    }

    @PostMapping("/{alertId}/triage")
    public Triage analyze(@PathVariable Long alertId) {
        return triage.analyze(alertId);
    }
}
