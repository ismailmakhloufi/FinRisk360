package tn.finrisk.riskservice.domain.enums;

/**
 * Operateur de comparaison d'une regle (check SQL ck_rr_operator).
 * Les constantes sont en Java, le symbole est ce qui est stocke en base
 * (via ComparisonOperatorConverter) : ">" en base, GREATER_THAN en Java.
 */
public enum ComparisonOperator {
    GREATER_THAN(">"),
    LESS_THAN("<"),
    EQUAL("="),
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN_OR_EQUAL("<="),
    BETWEEN("BETWEEN");

    private final String symbol;

    ComparisonOperator(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
