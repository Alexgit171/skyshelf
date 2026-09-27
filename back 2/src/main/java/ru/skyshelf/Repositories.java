package ru.skyshelf;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface Accounts extends JpaRepository<Account,String> {
    Optional<Account> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id=:id")
    Optional<Account> lockById(@Param("id") String id);
}
interface Folders extends JpaRepository<Folder,String> {
    List<Folder> findByOwnerIdOrderByNameAsc(String ownerId);
    boolean existsByOwnerIdAndName(String ownerId,String name);
}
interface Files extends JpaRepository<StoredFile,String> {
    List<StoredFile> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);
    List<StoredFile> findByProjectIdOrderByUpdatedAtDesc(String projectId);
    boolean existsByFolderId(String folderId);
}
interface Shares extends JpaRepository<ShareLink,String> {
    List<ShareLink> findByFileId(String fileId);
    void deleteByFileId(String fileId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShareLink s where s.token=:token")
    Optional<ShareLink> lockByToken(@Param("token") String token);
}
interface Projects extends JpaRepository<Project,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id=:id")
    Optional<Project> lockById(@Param("id") String id);
}
interface Members extends JpaRepository<Membership,String> {
    List<Membership> findByUserId(String userId);
    List<Membership> findByProjectId(String projectId);
    Optional<Membership> findByProjectIdAndUserId(String projectId,String userId);
}
interface Versions extends JpaRepository<FileVersion,String> {
    List<FileVersion> findByFileIdOrderByVersionNumberDesc(String fileId);
    Optional<FileVersion> findByIdAndFileId(String id,String fileId);
    void deleteByFileId(String fileId);
}
interface Tasks extends JpaRepository<ProjectTask,String> {
    List<ProjectTask> findByProjectIdOrderByPositionAscCreatedAtAsc(String projectId);
    void deleteByProjectId(String projectId);
}
interface Submissions extends JpaRepository<SubmissionLink,String> {
    List<SubmissionLink> findByProjectIdOrderByCreatedAtDesc(String projectId);
    void deleteByProjectId(String projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubmissionLink s where s.token=:token")
    Optional<SubmissionLink> lockByToken(@Param("token") String token);
}
interface Audits extends JpaRepository<AuditEvent,String> {}
