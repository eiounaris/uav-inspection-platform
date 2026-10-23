package com.uav.inspection.identity.web;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identities;
    private final JwtTokenService tokens;

    public IdentityController(IdentityService identities, JwtTokenService tokens) {
        this.identities = identities;
        this.tokens = tokens;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        CurrentUser user = identities.login(request.username(), request.password());
        var token = tokens.issue(user);
        return ApiResponse.success(new LoginResponse(token.accessToken(), token.expiresAt()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CurrentUser actor,
                                    @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        identities.logout(actor);
        return ApiResponse.success(null);
    }

    @GetMapping("/users/me")
    public ApiResponse<CurrentUser> me(@AuthenticationPrincipal CurrentUser actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(identities.authenticate(actor.id(), actor.tokenVersion()));
    }

    @PutMapping("/users/{id}/roles")
    public ApiResponse<CurrentUser> changeRoles(@AuthenticationPrincipal CurrentUser actor,
                                               @PathVariable String id,
                                               @Valid @RequestBody RolesRequest request) {
        return ApiResponse.success(identities.changeRoles(actor, id, request.roles(), request.expectedLockVersion()));
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest[credentials=REDACTED]";
        }
    }

    public record RolesRequest(@NotEmpty @Size(max = 6) List<@NotNull Role> roles,
                               @NotNull @PositiveOrZero Long expectedLockVersion) {}

    public record LoginResponse(String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "LoginResponse[accessToken=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
