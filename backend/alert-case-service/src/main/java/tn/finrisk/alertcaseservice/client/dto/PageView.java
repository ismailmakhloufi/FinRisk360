package tn.finrisk.alertcaseservice.client.dto;

import java.util.List;

/**
 * Page reduite d'un distant (Spring Page rend { content, totalElements, ... } :
 * on ne declare que ce dont on a besoin, les autres champs sont ignores
 * (Jackson Boot : FAIL_ON_UNKNOWN_PROPERTIES = false). Regle M0 : jamais
 * d'enum partage — les enums distantes arrivent en String.
 */
public record PageView<T>(List<T> content, long totalElements) {

    public List<T> contentOrEmpty() {
        return content == null ? List.of() : content;
    }
}
