package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.RefreshToken;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.RefreshTokenRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.security.LoginAttemptLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptLimiter loginAttemptLimiter;

    private static final Duration REFRESH_VALIDITY = Duration.ofDays(14);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record Tokens(String token, String refreshToken) {
    }

    public record Me(User user, List<Store> ownedStores, List<Employee> memberships) {
    }

    @Transactional
    public void signup(String username, String password, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }
        userRepository.save(new User(username, passwordEncoder.encode(password), role));
    }

    @Transactional
    public Tokens login(String username, String password) {
        if (loginAttemptLimiter.isBlocked(username)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            loginAttemptLimiter.recordFailure(username);
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        loginAttemptLimiter.reset(username);
        return issue(user);
    }

    // 쓴 리프레시 토큰은 바로 폐기하고 새로 발급한다
    @Transactional
    public Tokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        User user = stored.getUser();
        refreshTokenRepository.delete(stored);
        return issue(user);
    }

    // 다른 기기의 로그인을 끊기 위해 리프레시 토큰을 모두 지운다
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = findUser(username);
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    private Tokens issue(User user) {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        refreshTokenRepository.save(new RefreshToken(user, hash(refreshToken), LocalDateTime.now().plus(REFRESH_VALIDITY)));
        return new Tokens(jwtTokenProvider.createToken(user.getUsername(), user.getRole().name()), refreshToken);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public Me getMe(String username) {
        User user = findUser(username);
        return new Me(user, storeRepository.findByOwner_Username(username), employeeRepository.findByUserId(user.getId()));
    }

    // 로그인 후 역할 선택 화면용. 매장을 만들었거나 합류 신청을 했으면 역할이 섞이지 않도록 막는다
    @Transactional
    public String changeRole(String username, Role role) {
        User user = findUser(username);
        if (user.getRole() != role) {
            if (!storeRepository.findByOwner_Username(username).isEmpty()
                    || !employeeRepository.findByUserId(user.getId()).isEmpty()) {
                throw new IllegalArgumentException("매장을 등록했거나 합류 신청한 계정은 역할을 바꿀 수 없습니다.");
            }
            user.changeRole(role);
        }
        return jwtTokenProvider.createToken(user.getUsername(), user.getRole().name());
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    @Transactional
    public void updateDeviceToken(String username, String deviceToken) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.updateDeviceToken(deviceToken);
    }
}
