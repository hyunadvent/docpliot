package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.repository.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
public class UserManagementController {

    private final UserRepository userRepository;

    public UserManagementController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public String userManagement(Model model) {
        model.addAttribute("menu", "user-management");
        model.addAttribute("users", userRepository.findAllByOrderByCreatedAtDesc());
        return "admin/user-management";
    }

    @PostMapping("/{id}/approve")
    public String approveUser(@PathVariable Long id, RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            user.setApproved(true);
            userRepository.save(user);
        });
        ra.addFlashAttribute("success", "사용자가 승인되었습니다.");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String deleteUser(@PathVariable Long id, RedirectAttributes ra) {
        userRepository.deleteById(id);
        ra.addFlashAttribute("success", "사용자가 삭제되었습니다.");
        return "redirect:/admin/users";
    }
}
