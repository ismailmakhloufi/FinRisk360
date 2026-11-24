package tn.finrisk.riskservice.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Tradition Java <-> SQL pour ComparisonOperator :
 * ">" en base (valeur du check ck_rr_operator), GREATER_THAN en Java.
 * Declare sur le champ via @Convert (decouverte garantie par la spec JPA).
 */
@Converter
public class ComparisonOperatorConverter implements AttributeConverter<ComparisonOperator, String> {

    @Override
    public String convertToDatabaseColumn(ComparisonOperator attribute) {
        return attribute == null ? null : attribute.getSymbol();
    }

    @Override
    public ComparisonOperator convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (ComparisonOperator operator : ComparisonOperator.values()) {
            if (operator.getSymbol().equals(dbData)) {
                return operator;
            }
        }
        throw new IllegalStateException("Operateur de comparaison inconnu en base : " + dbData);
    }
}
