package ru.skyshelf;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin")
class AdminController {
    private final Accounts accounts;private final Files files;private final AuthController auth;private final Projects projects;private final Versions versions;private final Audits audits;private final AuditService audit;
    AdminController(Accounts a,Files f,AuthController c,Projects p,Versions v,Audits events,AuditService audit) {accounts=a;files=f;auth=c;projects=p;versions=v;audits=events;this.audit=audit;}
    record BlockInput(boolean blocked) {}
    @GetMapping("/users") List<Map<String,Object>> users(Authentication a) {auth.current(a);return accounts.findAll().stream().sorted(Comparator.comparing(u->u.createdAt)).map(AuthController::view).toList();}
    @PatchMapping("/users/{id}") Map<String,Object> block(Authentication a,@PathVariable String id,@RequestBody BlockInput p) {
        Account actor=auth.current(a);Account target=accounts.findById(id).orElseThrow(StorageService::missing);
        if(actor.id.equals(target.id)||target.role.equals("ADMIN")) throw StorageService.bad("Администратора нельзя заблокировать в интерфейсе.");
        target.blocked=p.blocked();accounts.save(target);audit.record(actor.id,null,p.blocked()?"ACCOUNT_BLOCKED":"ACCOUNT_UNBLOCKED","ACCOUNT",target.id,target.email);return AuthController.view(target);
    }
    @GetMapping("/stats") Map<String,Object> stats(Authentication a) {auth.current(a);return Map.of("users",accounts.count(),"files",files.count(),"versions",versions.count(),"projects",projects.count(),"auditEvents",audits.count(),"bytes",versions.findAll().stream().mapToLong(f->f.size).sum(),"encryption","AES-256-GCM");}
    record PlanInput(String plan) {}
    @GetMapping("/projects") List<Project> projects(Authentication a){auth.current(a);return projects.findAll();}
    @PatchMapping("/users/{id}/plan") @org.springframework.transaction.annotation.Transactional
    Map<String,Object> userPlan(Authentication a,@PathVariable String id,@RequestBody PlanInput p){Account actor=auth.current(a);validPlan(p.plan());Account u=accounts.lockById(id).orElseThrow(StorageService::missing);u.plan=p.plan();accounts.save(u);audit.record(actor.id,null,"PLAN_CHANGED","ACCOUNT",u.id,p.plan());return AuthController.view(u);}
    @PatchMapping("/projects/{id}/plan") @org.springframework.transaction.annotation.Transactional
    Project projectPlan(Authentication a,@PathVariable String id,@RequestBody PlanInput p){Account actor=auth.current(a);validPlan(p.plan());Project u=projects.lockById(id).orElseThrow(StorageService::missing);u.plan=p.plan();projects.save(u);audit.record(actor.id,u.id,"PLAN_CHANGED","PROJECT",u.id,p.plan());return u;}
    private void validPlan(String p){if(p==null||!Set.of("START","PERSONAL","TEAM").contains(p)) throw StorageService.bad("Неизвестный тариф.");}
}
