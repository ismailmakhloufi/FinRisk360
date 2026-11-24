package tn.finrisk.transactionservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import tn.finrisk.transactionservice.repository.TransactionRepository;
import tn.finrisk.transactionservice.web.dto.TransactionStatsResponse;

/**
 * Tests PURS des agregats du dashboard transactions : top beneficiaires,
 * zero-fill de la fenetre (7 ou 30 jours), volume + tendance, repartition
 * type/canal (tous les enumes presents, a 0 s'ils sont vides).
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private StatsService statsService;

    @Test
    void aucuneDonnee_tableauxPresentsMaisVides() {
        when(transactionRepository.topBeneficiaries(any(Pageable.class))).thenReturn(List.of());
        when(transactionRepository.statsByDaySince(any())).thenReturn(List.of());
        when(transactionRepository.count()).thenReturn(258L);
        when(transactionRepository.sumBetween(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countGroupByTransactionType()).thenReturn(List.of());
        when(transactionRepository.countGroupByChannel()).thenReturn(List.of());

        TransactionStatsResponse stats = statsService.dashboard(7);

        assertEquals(258, stats.totalTransactions());
        assertTrue(stats.topBeneficiaries().isEmpty());
        assertEquals(7, stats.activity().size());
        assertEquals(BigDecimal.ZERO, stats.volume());
        // base precedente vide : tendance inconnue (le front affiche "—")
        assertNull(stats.trendPct());
        // zero-fill : la veille a aujourd'hui est bien la 1re journee
        String expectedFirst = LocalDate.now(ZoneOffset.UTC).minusDays(6).toString();
        assertEquals(expectedFirst, stats.activity().getFirst().day());
        // zero-fill type/canal : tous les enumes sont des cles, a 0
        assertEquals(0L, stats.byType().get("PAYMENT"));
        assertEquals(0L, stats.byType().get("TRANSFER"));
        assertEquals(0L, stats.byChannel().get("MOBILE"));
        assertEquals(0L, stats.byChannel().get("API"));
    }

    @Test
    void agregats_MergesDansLaFenetre() {
        when(transactionRepository.topBeneficiaries(any(Pageable.class))).thenReturn(List.of(
                new Object[] {"ACME SARL", 9L, new BigDecimal("900.00")},
                new Object[] {"CONC-06", 9L, new BigDecimal("900.00")}));
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        when(transactionRepository.statsByDaySince(any())).thenReturn(List.of(
                new Object[] {today, 4L, new BigDecimal("400.00")},
                new Object[] {LocalDate.now(ZoneOffset.UTC).minusDays(2).toString(),
                        3L, new BigDecimal("75.50")}));
        when(transactionRepository.count()).thenReturn(258L);
        when(transactionRepository.sumBetween(any(), any())).thenReturn(new BigDecimal("380.40"));
        when(transactionRepository.countGroupByTransactionType()).thenReturn(List.<Object[]>of(
                new Object[] {"TRANSFER", 140L}));
        when(transactionRepository.countGroupByChannel()).thenReturn(List.<Object[]>of(
                new Object[] {"MOBILE", 96L},
                new Object[] {null, 3L}));

        TransactionStatsResponse stats = statsService.dashboard(7);

        assertEquals(2, stats.topBeneficiaries().size());
        assertEquals("ACME SARL", stats.topBeneficiaries().getFirst().name());
        assertEquals(9, stats.topBeneficiaries().getFirst().count());
        assertEquals(7, stats.activity().size());
        long totalJours = stats.activity().stream()
                .mapToLong(TransactionStatsResponse.DayCount::count).sum();
        assertEquals(7, totalJours);
        assertEquals(new BigDecimal("475.50"), stats.volume());
        // 475.50 vs 380.40 = +25 %
        assertEquals(25.0, stats.trendPct(), 1e-9);
        assertEquals(140L, stats.byType().get("TRANSFER"));
        assertEquals(96L, stats.byChannel().get("MOBILE"));
        // channel nullable : NULL -> « AUTRE », jamais la chaine « null »
        assertEquals(3L, stats.byChannel().get("AUTRE"));
    }

    @Test
    void fenetre30Jours_activiteEtzeroFillEtendus() {
        when(transactionRepository.topBeneficiaries(any(Pageable.class))).thenReturn(List.of());
        when(transactionRepository.statsByDaySince(any())).thenReturn(List.of());
        when(transactionRepository.count()).thenReturn(258L);
        when(transactionRepository.sumBetween(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countGroupByTransactionType()).thenReturn(List.of());
        when(transactionRepository.countGroupByChannel()).thenReturn(List.of());

        TransactionStatsResponse stats = statsService.dashboard(30);

        assertEquals(30, stats.activity().size());
        String expectedFirst = LocalDate.now(ZoneOffset.UTC).minusDays(29).toString();
        assertEquals(expectedFirst, stats.activity().getFirst().day());
        assertNull(stats.trendPct());
    }

    @Test
    void joursHorsBornes_normalises() {
        when(transactionRepository.topBeneficiaries(any(Pageable.class))).thenReturn(List.of());
        when(transactionRepository.statsByDaySince(any())).thenReturn(List.of());
        when(transactionRepository.count()).thenReturn(0L);
        when(transactionRepository.sumBetween(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countGroupByTransactionType()).thenReturn(List.of());
        when(transactionRepository.countGroupByChannel()).thenReturn(List.of());

        // <= 0 retombe sur 7 ; > 90 est borne a 90 (jamais de fenetre geante)
        assertEquals(7, statsService.dashboard(0).activity().size());
        assertEquals(90, statsService.dashboard(500).activity().size());
    }
}
