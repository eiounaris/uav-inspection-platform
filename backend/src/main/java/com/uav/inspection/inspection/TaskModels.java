package com.uav.inspection.inspection;

import com.uav.inspection.identity.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TaskModels {
    private TaskModels() {}

    public record RequirementInput(@NotBlank @Size(max = 200) String name, @NotNull Boolean required) {}
    public record RequirementUpdate(String id, @NotBlank @Size(max = 200) String name, @NotNull Boolean required) {}
    public record CreateTaskRequest(@NotBlank @Size(max = 200) String name,
                                    @NotBlank @Size(max = 16000) String scope,
                                    @NotNull @Size(max = 100) List<@NotNull @Valid RequirementInput> requirements) {}
    public record UpdateRequirementsRequest(
            @NotNull @Size(max = 100) List<@NotNull @Valid RequirementUpdate> requirements,
            @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record AssignTaskRequest(@NotBlank String operatorOrgId, @NotBlank String operatorAdminId,
                                     @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record ReplaceMembersRequest(@NotNull @Size(max = 100) List<@NotBlank String> userIds,
                                         @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record CreateSubtaskRequest(@NotBlank @Size(max = 200) String name,
                                       @NotEmpty @Size(max = 100) List<@NotBlank String> requirementIds,
                                       @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record CreateExecutionRequest(@NotBlank String droneId,
                                         @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record StartTaskRequest(@NotNull @PositiveOrZero Long expectedLockVersion) {}

    public record TaskSummary(String id, String ownerOrgId, String operatorOrgId, String name, String scope,
                               String status, String createdBy, long lockVersion, Instant createdAt, Instant updatedAt) {}
    public record TaskPage(List<TaskSummary> items, int page, int pageSize, long total) {}
    public record TaskDetail(String id, String ownerOrgId, String operatorOrgId, String name, String scope,
                              String status, String createdBy, long lockVersion, Instant createdAt, Instant updatedAt,
                              List<MemberView> members, List<RequirementView> requirements, List<SubtaskView> subtasks) {}
    public record MemberView(String userId, String orgId, String username, List<Role> roles) {}
    public record RequirementView(String id, String evidenceId, String taskId, String subtaskId, String name,
                                   boolean required, int nextVersionNo, long lockVersion) {}
    public record SubtaskView(String id, String taskId, String name, String currentExecutionId,
                               long lockVersion, ExecutionView execution) {}
    public record ExecutionView(String id, String subtaskId, String droneId, String status, int progress,
                                 Instant startedAt, Instant closedAt, long lockVersion) {}
}
