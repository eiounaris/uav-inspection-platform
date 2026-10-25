package com.uav.inspection.inspection;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.inspection.TaskModels.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/tasks")
    public ApiResponse<TaskPage> list(@AuthenticationPrincipal CurrentUser actor,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(tasks.list(actor, page, pageSize));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskDetail> get(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(tasks.get(actor, id));
    }

    @PostMapping("/tasks")
    public ApiResponse<TaskDetail> create(@AuthenticationPrincipal CurrentUser actor,
                                         @Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(tasks.create(actor, request));
    }

    @PutMapping("/tasks/{id}/requirements")
    public ApiResponse<TaskDetail> requirements(@AuthenticationPrincipal CurrentUser actor,
                                               @PathVariable String id,
                                               @Valid @RequestBody UpdateRequirementsRequest request) {
        return ApiResponse.success(tasks.updateRequirements(actor, id, request));
    }

    @PostMapping("/tasks/{id}/assign")
    public ApiResponse<TaskDetail> assign(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                         @Valid @RequestBody AssignTaskRequest request) {
        return ApiResponse.success(tasks.assign(actor, id, request));
    }

    @PutMapping("/tasks/{id}/members")
    public ApiResponse<TaskDetail> members(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                          @Valid @RequestBody ReplaceMembersRequest request) {
        return ApiResponse.success(tasks.replaceMembers(actor, id, request));
    }

    @PostMapping("/tasks/{id}/subtasks")
    public ApiResponse<TaskDetail> subtask(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                          @Valid @RequestBody CreateSubtaskRequest request) {
        return ApiResponse.success(tasks.createSubtask(actor, id, request));
    }

    @PostMapping("/subtasks/{id}/executions")
    public ApiResponse<ExecutionView> execution(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                               @Valid @RequestBody CreateExecutionRequest request) {
        return ApiResponse.success(tasks.createExecution(actor, id, request));
    }

    @GetMapping("/subtasks/{id}/executions")
    public ApiResponse<ExecutionHistoryPage> executions(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(tasks.executions(actor, id, page, pageSize));
    }

    @GetMapping("/tasks/{id}/progress")
    public ApiResponse<TaskProgress> progress(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(tasks.progress(actor, id));
    }

    @PostMapping("/tasks/{id}/start")
    public ApiResponse<TaskDetail> start(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id,
                                        @Valid @RequestBody StartTaskRequest request) {
        return ApiResponse.success(tasks.start(actor, id, request));
    }

    @PostMapping("/tasks/{id}/submit-acceptance")
    public ApiResponse<TaskDetail> submitAcceptance(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
                                                   @Valid @RequestBody SubmitAcceptanceRequest request) {
        return ApiResponse.success(tasks.submitAcceptance(actor,id,request));
    }

    @PostMapping("/tasks/{id}/complete")
    public ApiResponse<TaskDetail> complete(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
                                           @Valid @RequestBody CompleteTaskRequest request) {
        return ApiResponse.success(tasks.complete(actor,id,request));
    }
}
