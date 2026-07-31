package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ConsolePageController {
    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/runs/new")
    public String newRun() {
        return "run-new";
    }

    @GetMapping("/runs/{id}")
    public String runDetail() {
        return "run-detail";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/schedules")
    public String schedules() {
        return "schedules";
    }
}
