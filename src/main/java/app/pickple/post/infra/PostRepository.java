package app.pickple.post.infra;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** package-private. 바깥은 {@link app.pickple.post.domain.PostStore} 만 본다. */
interface PostRepository extends JpaRepository<PostEntity, Long> {

    boolean existsByIdAndDeletedAtIsNull(Long id);

    /** 수정·삭제 경합을 직렬화한다. 삭제 여부를 보지 않고 잠근다 — 판정은 위층이 현재 값으로 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PostEntity p WHERE p.id = :id")
    Optional<PostEntity> findByIdForUpdate(@Param("id") Long id);
}
