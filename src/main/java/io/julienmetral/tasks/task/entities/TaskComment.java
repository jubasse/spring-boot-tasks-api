package io.julienmetral.tasks.task.entities;

import io.julienmetral.tasks.identity.entities.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

@Entity
@Table(
        name = "task_comments",
        indexes = {@Index(name = "task_comments_task_idIDX", columnList = "task_id")})
@Getter
@Setter
@NoArgsConstructor
public class TaskComment {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // EAGER: Hibernate refuses a LAZY to-one towards a @SoftDelete entity (see TaskAttachment.task)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "task_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "task_comments_taskFK"))
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "author_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "task_comments_authorFK"))
    private UserProfile author;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "task_comment_mentions",
            joinColumns =
                    @JoinColumn(
                            name = "comment_id",
                            foreignKey = @ForeignKey(name = "task_comment_mentions_commentFK")),
            inverseJoinColumns =
                    @JoinColumn(
                            name = "user_id",
                            foreignKey = @ForeignKey(name = "task_comment_mentions_userFK")))
    private Set<UserProfile> mentions = new HashSet<>();

    @OneToMany(mappedBy = "comment")
    @OrderBy("createdAt ASC")
    private List<TaskAttachment> attachments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;
}
