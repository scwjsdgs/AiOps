package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.dto.AuthRequest;
import com.opsagent.dto.AuthResponse;
import com.opsagent.entity.User;
import com.opsagent.service.UserService;
import com.opsagent.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {


    private final UserService userService;

    private final JwtUtil jwtUtil;

    /**
     * 登录。
     *
     * 失败时返回**真实的 HTTP 401**，而不是 HTTP 200 + body code=401。
     * body 里仍保留 code=401，前端 request.js 既看 res.code 也看 error.response.status，
     * 两条路都能正确登出，行为不变；但监控/脚本只看 HTTP 状态码时不再误判为成功。
     *
     * 注意：用户不存在与密码错误返回**同一个** message，避免账号枚举
     * （能区分出"用户名对不对"等于给攻击者一个探测接口）。
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> login(@RequestBody AuthRequest request) {
        Optional<User> userOpt = userService.findByUsername(request.getUsername());
        if (userOpt.isEmpty()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Invalid username or password")));
        }
        User user = userOpt.get();
        if (!userService.checkPassword(request.getPassword(), user.getPassword())) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Invalid username or password")));
        }
        String token = jwtUtil.generateToken(user.getUsername());
        return Mono.just(ResponseEntity.ok(
                ApiResponse.success(new AuthResponse(token, user.getUsername(), user.getRole()))));
    }
}