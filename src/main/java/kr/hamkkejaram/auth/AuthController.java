package kr.hamkkejaram.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.hamkkejaram.auth.AuthDtos.LoginRequest;
import kr.hamkkejaram.auth.AuthDtos.RefreshRequest;
import kr.hamkkejaram.auth.AuthDtos.SignupRequest;
import kr.hamkkejaram.auth.AuthDtos.TokenResponse;
import kr.hamkkejaram.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        rateLimiter.checkAuthRequest(http.getRemoteAddr());
        return authService.signup(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        rateLimiter.checkAuthRequest(http.getRemoteAddr());
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        rateLimiter.checkAuthRequest(http.getRemoteAddr());
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
    }
}
