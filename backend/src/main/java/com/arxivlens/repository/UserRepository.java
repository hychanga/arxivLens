package com.arxivlens.repository;

import com.arxivlens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Stable lookup key for OAuth users: the provider's {@code sub} (subject)
     * claim never changes for a given identity, while the email might (Google
     * lets users change primary email; we don't want that to detach the user
     * from their library).
     */
    Optional<User> findByOauthProviderAndOauthSubject(String oauthProvider, String oauthSubject);
}
