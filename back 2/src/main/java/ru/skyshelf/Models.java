package ru.skyshelf;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Сущности БД. API возвращает отдельные DTO, а не эти объекты. */
final class Models { private Models() {} }

@Entity @Table(name="accounts")
class Account {
    @Id public String id = UUID.randomUUID().toString();
    @Column(nullable=false, unique=true, length=180) public String email;
    @Column(nullable=false) public String passwordHash;
    @Column(nullable=false, length=80) public String name;
    public String role = "USER";
    public String plan = "START";
    public boolean blocked;
    public String theme = "dark";
    public String accent = "blue";
    @Column(length=700) public String twoFactorSecret;
    public boolean twoFactorEnabled;
    public Instant lastLoginAt;
    public Instant createdAt = Instant.now();
    protected Account() {}
}

@Entity @Table(name="folders")
class Folder {
    @Id public String id = UUID.randomUUID().toString();
    @Column(nullable=false) public String ownerId;
    public String projectId;
    @Column(nullable=false, length=120) public String name;
    public String color = "blue";
    protected Folder() {}
}

@Entity @Table(name="stored_files", indexes=@Index(columnList="ownerId"))
class StoredFile {
    @Id public String id = UUID.randomUUID().toString();
    @Column(nullable=false) public String ownerId;
    public String projectId;
    public String folderId;
    @Column(nullable=false, length=180) public String name;
    @Column(nullable=false) public String objectKey;
    public long size;
    @Column(length=100) public String mimeType = "application/octet-stream";
    @Column(length=64) public String checksum;
    public int versionCount = 1;
    public String createdById;
    public boolean favorite;
    public boolean trashed;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    protected StoredFile() {}
}

@Entity @Table(name="share_links")
class ShareLink {
    /** SHA-256 от публичного токена. Сам токен в БД не хранится. */
    @Id public String token;
    @Column(nullable=false) public String fileId;
    public Instant createdAt = Instant.now();
    public Instant expiresAt;
    public int maxDownloads;
    public int downloadCount;
    public String passwordHash;
    protected ShareLink() {}
}

@Entity @Table(name="projects")
class Project {
    @Id public String id=UUID.randomUUID().toString();
    @Column(nullable=false) public String ownerId;
    @Column(nullable=false,length=120) public String name;
    @Column(length=700) public String description = "";
    @Column(length=120) public String course = "";
    @Column(length=120) public String teacher = "";
    public Instant deadline;
    public String accent = "blue";
    public String cover = "orb";
    public String status = "IN_PROGRESS";
    public String finalFileId;
    public String plan="START";
    public Instant createdAt=Instant.now();
    protected Project() {}
}

@Entity @Table(name="memberships",uniqueConstraints=@UniqueConstraint(columnNames={"projectId","userId"}))
class Membership {
    @Id public String id=UUID.randomUUID().toString();
    @Column(nullable=false) public String projectId;
    @Column(nullable=false) public String userId;
    public String role="READER";
    protected Membership() {}
}

@Entity @Table(name="file_versions",uniqueConstraints=@UniqueConstraint(columnNames={"fileId","versionNumber"}),indexes=@Index(columnList="fileId"))
class FileVersion {
    @Id public String id=UUID.randomUUID().toString();
    @Column(nullable=false) public String fileId;
    @Column(nullable=false) public String objectKey;
    public int versionNumber;
    public long size;
    @Column(length=100) public String mimeType="application/octet-stream";
    @Column(length=64) public String checksum;
    public String createdById;
    @Column(length=240) public String note="";
    public Instant createdAt=Instant.now();
    protected FileVersion() {}
}

@Entity @Table(name="project_tasks",indexes=@Index(columnList="projectId"))
class ProjectTask {
    @Id public String id=UUID.randomUUID().toString();
    @Column(nullable=false) public String projectId;
    @Column(nullable=false,length=180) public String title;
    public boolean completed;
    public String assigneeId;
    public Instant dueAt;
    public int position;
    public Instant createdAt=Instant.now();
    protected ProjectTask() {}
}

@Entity @Table(name="submission_links",indexes=@Index(columnList="projectId"))
class SubmissionLink {
    /** SHA-256 от публичного токена. */
    @Id public String token;
    @Column(nullable=false) public String projectId;
    public Instant createdAt=Instant.now();
    public Instant expiresAt;
    public int views;
    protected SubmissionLink() {}
}

@Entity @Table(name="audit_events",indexes={@Index(columnList="actorId"),@Index(columnList="projectId")})
class AuditEvent {
    @Id public String id=UUID.randomUUID().toString();
    public String actorId;
    public String projectId;
    @Column(nullable=false,length=60) public String action;
    @Column(nullable=false,length=40) public String targetType;
    public String targetId;
    @Column(length=500) public String details="";
    public Instant createdAt=Instant.now();
    protected AuditEvent() {}
}
