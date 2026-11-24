package tn.finrisk.authservice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.finrisk.authservice.domain.AppUser;

/** Acces a "users" — table exclusive du auth-service. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<AppUser> findAllByOrderByUserIdAsc();
}
