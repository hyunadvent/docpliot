package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.entity.UserEntity;
import com.hancom.ai.docpilot.docpilot.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.security.auth.login.AccountException;
import java.time.LocalDateTime;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            HttpServletRequest request,
                            Model model) {
        if (error != null) {
            Exception ex = (Exception) request.getSession()
                    .getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
            if (ex instanceof org.springframework.security.authentication.DisabledException) {
                model.addAttribute("errorMessage", ex.getMessage());
            } else {
                model.addAttribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다.");
            }
        }
        if (logout != null) {
            model.addAttribute("logoutMessage", "로그아웃되었습니다.");
        }
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username,
                           @RequestParam String password,
                           @RequestParam String email,
                           @RequestParam String name,
                           RedirectAttributes redirectAttributes) {
        if (userRepository.existsByUsername(username)) {
            redirectAttributes.addFlashAttribute("error", "이미 존재하는 아이디입니다.");
            return "redirect:/register";
        }

        userRepository.save(UserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .email(email)
                .name(name)
                .role("ROLE_USER")
                .approved(false)
                .createdAt(LocalDateTime.now())
                .build());

        redirectAttributes.addFlashAttribute("success",
                "회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.");
        return "redirect:/login";
    }
}
