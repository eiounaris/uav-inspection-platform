package com.uav.inspection.config;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.security.JwtTokenService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    @Profile("database")
    SecurityFilterChain authenticatedApi(HttpSecurity http, JwtTokenService tokens,
                                          IdentityService identities, JsonMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            ErrorCode error = failure instanceof AuthenticationServiceException
                    ? ErrorCode.DEPENDENCY_UNAVAILABLE : ErrorCode.UNAUTHORIZED;
            writeError(response, json, error);
        };
        AccessDeniedHandler forbidden = (request, response, failure) ->
                writeError(response, json, ErrorCode.FORBIDDEN);
        var failureHandler = new AuthenticationEntryPointFailureHandler(unauthorized);
        failureHandler.setRethrowAuthenticationServiceException(false);

        stateless(http);
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resource -> resource
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        })
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.decoder(tokens.decoder()).jwtAuthenticationConverter(token -> {
                            try {
                                CurrentUser user = identities.authenticate(token.getSubject(),
                                        ((Number) token.getClaims().get("tokenVersion")).longValue());
                                var authorities = user.roles().stream()
                                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                                return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
                            } catch (BusinessException failure) {
                                if (failure.errorCode() == ErrorCode.UNAUTHORIZED) {
                                    throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                                }
                                throw new AuthenticationServiceException("Account state unavailable");
                            } catch (DataAccessException failure) {
                                throw new AuthenticationServiceException("Account state unavailable");
                            }
                        })));
        return http.build();
    }

    // The HTTP foundation and standalone Gateway probe have no database business controllers.
    @Bean
    @Profile("!database")
    SecurityFilterChain foundationApi(HttpSecurity http) throws Exception {
        stateless(http);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static void stateless(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    private static void writeError(HttpServletResponse response, JsonMapper json, ErrorCode error)
            throws IOException {
        response.setStatus(error.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        if (error == ErrorCode.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), ApiResponse.error(error.name(), error.message(), null));
    }
}
