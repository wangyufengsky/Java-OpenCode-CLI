package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ConsolePageController {
    private final ChainCatalog chainCatalog;
    private final WorkflowRunRepository repository;

    public ConsolePageController(ChainCatalog chainCatalog, WorkflowRunRepository repository) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("runs", repository.listRuns());
        return "dashboard";
    }

    @GetMapping("/runs/new")
    public String newRun(@RequestParam(required = false, defaultValue = "git-code-contribution-report") String chainId, Model model) {
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("chainId", chainId);
        return "run-new";
    }

    @GetMapping("/runs/{id}")
    public String runDetail(@PathVariable long id, Model model) {
        model.addAttribute("run", repository.findRun(id).orElseThrow());
        model.addAttribute("events", repository.listEvents(id));
        model.addAttribute("tasks", repository.listTaskStatuses(id));
        return "run-detail";
    }

    @GetMapping("/history")
    public String history(Model model) {
        model.addAttribute("runs", repository.listRuns());
        return "history";
    }
}
