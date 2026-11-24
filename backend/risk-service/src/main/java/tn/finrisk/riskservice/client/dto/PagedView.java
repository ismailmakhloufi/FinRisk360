package tn.finrisk.riskservice.client.dto;

import java.util.List;

/**
 * Shape paginee de Spring Data ({ "content": [...], "totalElements": N, ... }).
 * On ne lit que "content" ; Jackson ignore les autres champs par defaut.
 */
public record PagedView<T>(List<T> content) {
}
