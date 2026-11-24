package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tn.finrisk.transactionservice.client.AccountClient;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.domain.Transaction;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.repository.MerchantRepository;
import tn.finrisk.transactionservice.repository.TransactionRepository;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;
import tn.finrisk.transactionservice.web.error.BalanceChangeRefusedException;
import tn.finrisk.transactionservice.web.error.DownstreamUnavailableException;
import tn.finrisk.transactionservice.web.error.InvalidRibException;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;
import tn.finrisk.transactionservice.web.mapper.TransactionMapper;

/**
 * Tests UNITAIRES : aucune base, aucun HTTP. Seule la logique est verifiee.
 * Le mapper reel est utilise, les repositories sont simules (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private AccountClient accountClient;

    @Mock
    private FinancialAttemptGuard financialGuard;

    private TransactionService transactionService;
    private InMemoryFinancialJournal journal;

    @BeforeEach
    void setUp() {
        journal = new InMemoryFinancialJournal();
        transactionService = new TransactionService(
                transactionRepository, merchantRepository, new TransactionMapper(),
                mock(JdbcTemplate.class), accountClient, financialGuard, journal);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void create_sansMarchand_naitPENDINGetSauvegarde() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setTransactionId(42L);
            return saved;
        });

        TransactionResponse response = transactionService.create(request(null));

        assertThat(response.transactionId()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(response.accountId()).isEqualTo(7L);
        assertThat(response.amount()).isEqualByComparingTo("88.75");
        assertThat(response.merchantId()).isNull();
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void create_marchandInconnu_leveNotFoundEtNeSauvegardePas() {
        when(merchantRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(request(999L)))
                .isInstanceOf(MerchantNotFoundException.class)
                .hasMessageContaining("999");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_marchandConnu_associeLeMarchand() {
        Merchant merchant = merchant(7L, "Tunis Market");
        when(merchantRepository.findById(7L)).thenReturn(Optional.of(merchant));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setTransactionId(10L);
            return saved;
        });

        TransactionResponse response = transactionService.create(request(7L));

        assertThat(response.transactionId()).isEqualTo(10L);
        assertThat(response.merchantId()).isEqualTo(7L);
    }

    @Test
    void create_ribInterne_resoluEnCompteBeneficiaire() {
        when(accountClient.resolveByRib(any())).thenReturn(42L);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setTransactionId(1L);
            return saved;
        });

        TransactionResponse response = transactionService.create(requestWithRib(ribFor("12345678901")));

        assertThat(response.beneficiaryAccountId()).isEqualTo(42L);
        assertThat(response.beneficiaryRIB()).isEqualTo(ribFor("12345678901"));
        assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void create_ribExterne_bienForme_beneficiaireNull() {
        when(accountClient.resolveByRib(any())).thenReturn(null);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setTransactionId(2L);
            return saved;
        });

        TransactionResponse response = transactionService.create(requestWithRib(ribFor("99999999999")));

        assertThat(response.beneficiaryAccountId()).isNull();
        assertThat(response.beneficiaryRIB()).isEqualTo(ribFor("99999999999"));
    }

    @Test
    void create_ribMalformeLongueur_leve400EtNeSauvegardePas() {
        assertThatThrownBy(() -> transactionService.create(requestWithRib("TN 0109 001 1234567890")))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("20 chiffres");

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(accountClient);
    }

    @Test
    void create_ribCleFausse_leve400EtNeSauvegardePas() {
        String rib = ribFor("12345678901");
        String badRib = rib.substring(0, rib.length() - 2) + "00";

        assertThatThrownBy(() -> transactionService.create(requestWithRib(badRib)))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("cle de controle");

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(accountClient);
    }

    @Test
    void create_ribVersSoiMeme_leve400() {
        // Le RIB resout sur le compte source (7L) : virement vers soi-meme.
        when(accountClient.resolveByRib(any())).thenReturn(7L);

        assertThatThrownBy(() -> transactionService.create(requestWithRib(ribFor("12345678901"))))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("compte source");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_ribFourni_precedenceSurBeneficiaireAccountIdBrut() {
        // Meme si un beneficiaryAccountId brut est fourni, le RIB fait foi.
        when(accountClient.resolveByRib(any())).thenReturn(42L);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setTransactionId(3L);
            return saved;
        });
        TransactionCreateRequest request = new TransactionCreateRequest(7L, null,
                new BigDecimal("88.75"), "TND", TransactionType.TRANSFER, "fr",
                TransactionChannel.API, "41.104.10.7", "device-1", "B", 999L,
                ribFor("12345678901"));

        TransactionResponse response = transactionService.create(request);

        assertThat(response.beneficiaryAccountId()).isEqualTo(42L);
        assertThat(response.beneficiaryRIB()).isNotNull();
    }

    @Test
    void changeStatus_transactionTerminee_refuseModification() {
        Transaction transaction = transaction(3L, TransactionStatus.COMPLETED);
        when(transactionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.changeStatus(3L, TransactionStatus.FAILED))
                .isInstanceOf(TransactionNotModifiableException.class)
                .hasMessageContaining("COMPLETED");

        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void changeStatus_pendingVersCompleted_reguleParDEBIT() {
        Transaction transaction = transaction(4L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response =
                transactionService.changeStatus(4L, TransactionStatus.COMPLETED);

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("DEBIT"));
        verify(accountClient, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_pendingVersCompleted_avecBeneficiaireInterne_crediteEgalement() {
        Transaction transaction = transaction(10L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transactionService.changeStatus(10L, TransactionStatus.COMPLETED);

        // Le debit source part en premier (operation a risque), puis le credit.
        InOrder order = inOrder(accountClient);
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("DEBIT"));
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(99L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(accountClient, times(2)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_pendingVersCompleted_deposit_crediteLaSource() {
        Transaction transaction = transaction(6L, TransactionStatus.PENDING);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        when(transactionRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transactionService.changeStatus(6L, TransactionStatus.COMPLETED);

        verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(accountClient, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_pendingVersCompleted_versSoiMeme_neBougeRien() {
        Transaction transaction = transaction(12L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(7L);
        when(transactionRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transactionService.changeStatus(12L, TransactionStatus.COMPLETED);

        verifyNoInteractions(accountClient);
    }

    @Test
    void changeStatus_annulation_neBougeAucunSolde() {
        Transaction transaction = transaction(5L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response =
                transactionService.changeStatus(5L, TransactionStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        // Aucun solde n'avait bouge en PENDING : rien a reverser.
        verifyNoInteractions(accountClient);
    }

    @Test
    void changeStatus_annulation_deposit_neBougeAucunSolde() {
        Transaction transaction = transaction(13L, TransactionStatus.PENDING);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        when(transactionRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response =
                transactionService.changeStatus(13L, TransactionStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        verifyNoInteractions(accountClient);
    }

    @Test
    void changeStatus_regulationEchoue_neSauvegardePas() {
        Transaction transaction = transaction(7L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(transaction));
        doThrow(new DownstreamUnavailableException("account-service", new RuntimeException("down")))
                .when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(7L, TransactionStatus.COMPLETED))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void changeStatus_regulationRefusee_neSauvegardePas() {
        Transaction transaction = transaction(9L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(transaction));
        doThrow(new BalanceChangeRefusedException("Compte 957398324794 FROZEN (409)",
                new RuntimeException("409")))
                .when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(9L, TransactionStatus.COMPLETED))
                .isInstanceOf(BalanceChangeRefusedException.class)
                .hasMessageContaining("FROZEN");

        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void creditBeneficiaireTimeout_neCompensePasAveuglement() {
        Transaction transaction = transaction(11L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            if (Long.valueOf(99L).equals(invocation.getArgument(1))) {
                throw new DownstreamUnavailableException("account-service", new RuntimeException("timeout"));
            }
            return null;
        }).when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());
        assertThatThrownBy(() -> transactionService.changeStatus(11L, TransactionStatus.COMPLETED))
                .isInstanceOf(DownstreamUnavailableException.class);
        verify(accountClient, never()).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(financialGuard).begin(11L, "COMPLETED");
    }

    @Test
    void erreurInattendueBeneficiaire_neDeclenchePasDeCompensationAveugle() {
        Transaction transaction = transaction(24L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(24L)).thenReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            if (Long.valueOf(99L).equals(invocation.getArgument(1))) {
                throw new IllegalStateException("etat inconnu");
            }
            return null;
        }).when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(24L, TransactionStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
        verify(accountClient, never()).applyBalanceIdempotent(any(), eq(7L), any(), eq("CREDIT"));
        verify(financialGuard, never()).finish(24L);
    }

    @Test
    void reverseCompensationIncertaine_bloqueTouteReprise() {
        Transaction transaction = transaction(25L, TransactionStatus.COMPLETED);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(25L)).thenReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            Long accountId = invocation.getArgument(1);
            String direction = invocation.getArgument(3);
            if (accountId.equals(7L)) {
                throw new BalanceChangeRefusedException("source refusee", new RuntimeException("409"));
            }
            if (direction.equals("CREDIT")) {
                throw new DownstreamUnavailableException("account-service", new RuntimeException("timeout"));
            }
            return null;
        }).when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());
        assertThatThrownBy(() -> transactionService.changeStatus(25L, TransactionStatus.CANCELLED))
                .isInstanceOf(BalanceChangeRefusedException.class);
        assertThat(journal.hasCompensation(FinancialOperationJournal.attemptFor(25L))).isTrue();
        when(financialGuard.pending(25L)).thenReturn(
                Optional.of(new FinancialAttemptGuard.Pending("CANCELLED")));
        assertThatThrownBy(() -> transactionService.changeStatus(25L, TransactionStatus.CANCELLED))
                .isInstanceOf(TransactionNotModifiableException.class)
                .hasMessageContaining("MOUVEMENT_INCERTAIN");
        verify(accountClient, times(3)).applyBalanceIdempotent(any(), any(), any(), any());
        verify(financialGuard, never()).finish(25L);
    }

    @Test
    void changeStatus_creditBeneficiaireEchoue_compenseLaSourcePuisEchoue() {
        Transaction transaction = transaction(11L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(transaction));
        // Le debit de la source passe, seul le credit du beneficiaire est refuse.
        doAnswer(invocation -> {
            Object accountId = invocation.getArgument(1);
            if (accountId != null && accountId.equals(99L)) {
                throw new BalanceChangeRefusedException("Compte 99 FROZEN (409)",
                        new RuntimeException("409"));
            }
            return null;
        }).when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(11L, TransactionStatus.COMPLETED))
                .isInstanceOf(BalanceChangeRefusedException.class);

        // debit source -> credit beneficiaire refuse -> compensation CREDIT de la source.
        InOrder order = inOrder(accountClient);
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("DEBIT"));
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(99L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
        // Aucun effacement du journal ou liberation du marqueur avant reconciliation.
        verify(financialGuard, never()).finish(11L);
        assertThat(journal.row(FinancialOperationJournal.attemptFor(11L), "SETTLE_SOURCE")).isNotNull();
        assertThat(journal.hasCompensation(FinancialOperationJournal.attemptFor(11L))).isTrue();
    }

    @Test
    void changeStatus_reponsePerdue_rejoueAvecLaMemeClePuisTermine() {
        Transaction transaction = transaction(21L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(financialGuard.pending(21L)).thenReturn(Optional.empty(),
                Optional.of(new FinancialAttemptGuard.Pending("COMPLETED")));
        doThrow(new DownstreamUnavailableException("account-service", new RuntimeException("timeout")))
                .doNothing()
                .when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        // 1er essai : reponse perdue -> transaction NON validee, marqueur conserve.
        assertThatThrownBy(() -> transactionService.changeStatus(21L, TransactionStatus.COMPLETED))
                .isInstanceOf(DownstreamUnavailableException.class);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);

        // 2e essai (MEME cible) : reprise idempotente autorisee.
        TransactionResponse response =
                transactionService.changeStatus(21L, TransactionStatus.COMPLETED);

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
        verify(accountClient, times(2)).applyBalanceIdempotent(keys.capture(), any(), any(), any());
        // Le rejeu utilise la MEME cle : account-service n'applique qu'un debit.
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        // Le marqueur existant est REUTILISE, jamais recree pendant une reprise.
        verify(financialGuard, times(1)).begin(21L, "COMPLETED");
    }

    @Test
    void changeStatus_resultatIncertain_annulationBloqueePourReconciliation() {
        Transaction transaction = transaction(22L, TransactionStatus.PENDING);
        when(transactionRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(transaction));
        when(financialGuard.pending(22L)).thenReturn(
                Optional.of(new FinancialAttemptGuard.Pending("COMPLETED")));

        // Tant que l'etat reel n'est pas connu : ni refus ni annulation —
        // l'argent peut avoir bouge cote account-service.
        assertThatThrownBy(() -> transactionService.changeStatus(22L, TransactionStatus.CANCELLED))
                .isInstanceOf(TransactionNotModifiableException.class)
                .hasMessageContaining("MOUVEMENT_INCERTAIN");

        verifyNoInteractions(accountClient);
        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void compensationIncertaine_bloqueMemeLeRejeuDeLaMemeCible() {
        Transaction transaction = transaction(23L, TransactionStatus.PENDING);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(23L)).thenReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            Long accountId = invocation.getArgument(1);
            String direction = invocation.getArgument(3);
            if (Long.valueOf(99L).equals(accountId)) {
                throw new BalanceChangeRefusedException("Compte 99 FROZEN (409)",
                        new RuntimeException("409"));
            }
            if ("CREDIT".equals(direction)) {
                throw new DownstreamUnavailableException("account-service",
                        new RuntimeException("timeout"));
            }
            return null;
        }).when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(23L, TransactionStatus.COMPLETED))
                .isInstanceOf(BalanceChangeRefusedException.class);

        // Compensation non confirmee : etat reel inconnu -> aucun rejeu, meme
        // pour la meme cible, sans reconciliation humaine.
        verify(financialGuard, never()).finish(any());
        assertThat(journal.hasCompensation(FinancialOperationJournal.attemptFor(23L))).isTrue();
        when(financialGuard.pending(23L)).thenReturn(
                Optional.of(new FinancialAttemptGuard.Pending("COMPLETED")));
        assertThatThrownBy(() -> transactionService.changeStatus(23L, TransactionStatus.COMPLETED))
                .isInstanceOf(TransactionNotModifiableException.class)
                .hasMessageContaining("MOUVEMENT_INCERTAIN");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void changeStatus_annulation_dejaAnnulee_idempotentSansSolde() {
        Transaction transaction = transaction(8L, TransactionStatus.CANCELLED);
        when(transactionRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(transaction));

        // CANCELLED -> CANCELLED : no-op 200 (l'orchestrateur de refus de
        // groupe est rejouable apres un echec partiel).
        TransactionResponse response =
                transactionService.changeStatus(8L, TransactionStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        verifyNoInteractions(accountClient);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void changeStatus_completedVersCancelled_rembourseLaSourceParCREDIT() {
        Transaction transaction = transaction(14L, TransactionStatus.COMPLETED);
        when(transactionRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response =
                transactionService.changeStatus(14L, TransactionStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        // TRANSFER : la source avait ete DEBITEE -> remboursement CREDIT.
        verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(accountClient, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_completedDepositVersCancelled_retrodebitLaSource() {
        Transaction transaction = transaction(15L, TransactionStatus.COMPLETED);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        when(transactionRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response =
                transactionService.changeStatus(15L, TransactionStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        // DEPOSIT : la source avait ete CREDITEE -> remboursement DEBIT.
        verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("DEBIT"));
        verify(accountClient, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_completedAvecBeneficiaire_reverseBeneficiairePuisSource() {
        Transaction transaction = transaction(16L, TransactionStatus.COMPLETED);
        transaction.setBeneficiaryAccountId(99L);
        when(transactionRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transactionService.changeStatus(16L, TransactionStatus.CANCELLED);

        // Ordre inverse de la REGULATION : beneficiaire d'abord (risque :
        // il a pu depenser), puis source.
        InOrder order = inOrder(accountClient);
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(99L), eq(new BigDecimal("88.75")), eq("DEBIT"));
        order.verify(accountClient).applyBalanceIdempotent(any(), eq(7L), eq(new BigDecimal("88.75")), eq("CREDIT"));
        verify(accountClient, times(2)).applyBalanceIdempotent(any(), any(), any(), any());
    }

    @Test
    void changeStatus_remoursementEchoue_neMetPasAJourLaTransaction() {
        Transaction transaction = transaction(17L, TransactionStatus.COMPLETED);
        when(transactionRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(transaction));
        doThrow(new BalanceChangeRefusedException("Compte 7 FROZEN (409)",
                new RuntimeException("409")))
                .when(accountClient).applyBalanceIdempotent(any(), any(), any(), any());

        assertThatThrownBy(() -> transactionService.changeStatus(17L, TransactionStatus.CANCELLED))
                .isInstanceOf(BalanceChangeRefusedException.class);

        // Jamais d'annulation sans remboursement effectue (rollback).
        verify(transactionRepository, never()).save(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void changeStatus_completedVersFailed_refuseToujours() {
        Transaction transaction = transaction(18L, TransactionStatus.COMPLETED);
        when(transactionRepository.findByIdForUpdate(18L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.changeStatus(18L, TransactionStatus.FAILED))
                .isInstanceOf(TransactionNotModifiableException.class)
                .hasMessageContaining("COMPLETED");

        verifyNoInteractions(accountClient);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void getById_inconnu_leveNotFound() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getById(99L))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getAll_composeLaSpecification_etDelaisseAuRepository() {
        when(transactionRepository.findAll(
                any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transaction(1L, TransactionStatus.PENDING))));

        PageRequest pageable = PageRequest.of(0, 20);
        Page<TransactionResponse> page = transactionService.getAll(
                pageable, 5L, TransactionStatus.PENDING, null, null, null, null);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().status()).isEqualTo(TransactionStatus.PENDING);
        verify(transactionRepository)
                .findAll(any(Specification.class), eq(pageable));
        verify(transactionRepository, never()).findAll(any(Pageable.class));
    }

    private TransactionCreateRequest request(Long merchantId) {
        return new TransactionCreateRequest(7L, merchantId, new BigDecimal("88.75"), "TND",
                TransactionType.TRANSFER, "fr", TransactionChannel.API,
                "41.104.10.7", "device-1", "Beneficiaire", null, null);
    }

    private TransactionCreateRequest requestWithRib(String rib) {
        return new TransactionCreateRequest(7L, null, new BigDecimal("88.75"), "TND",
                TransactionType.TRANSFER, "fr", TransactionChannel.API,
                "41.104.10.7", "device-1", "Beneficiaire", null, rib);
    }

    /** RIB TN canonique pour 11 chiffres de compte ( meme formule que le
     *  simulateur : banque 0109, agence 001, cle modulo 97). */
    private static String ribFor(String accountDigits11) {
        String body = "0109001" + accountDigits11;
        String key = String.format("%02d", (Long.parseLong(body) % 97) + 1);
        String d = body + key;
        return "TN " + d.substring(0, 4) + " " + d.substring(4, 7) + " "
                + d.substring(7, 18) + " " + d.substring(18);
    }

    private Transaction transaction(Long id, TransactionStatus status) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(id);
        transaction.setAccountId(7L);
        transaction.setAmount(new BigDecimal("88.75"));
        transaction.setCurrency("TND");
        transaction.setTransactionType(TransactionType.TRANSFER);
        transaction.setCountry("FR");
        transaction.setStatus(status);
        transaction.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
        transaction.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return transaction;
    }

    private Merchant merchant(Long id, String name) {
        Merchant merchant = new Merchant();
        merchant.setMerchantId(id);
        merchant.setMerchantName(name);
        return merchant;
    }
}
