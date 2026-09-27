package ru.skyshelf;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class ProjectService {
    final Projects projects;final Members members;final Accounts accounts;final Tasks tasks;final Files files;
    final Submissions submissions;final CryptoService crypto;final AuditService audit;
    ProjectService(Projects p,Members m,Accounts a,Tasks t,Files f,Submissions s,CryptoService c,AuditService audit){projects=p;members=m;accounts=a;tasks=t;files=f;submissions=s;crypto=c;this.audit=audit;}
    Projects projectRepository(){return projects;}
    Members memberRepository(){return members;}
    Accounts accountRepository(){return accounts;}
    Tasks taskRepository(){return tasks;}

    String role(String id,String user) {
        Project p=projects.findById(id).orElseThrow(StorageService::missing);
        if(accounts.findById(p.ownerId).map(a->a.blocked).orElse(true))throw StorageService.missing();
        return members.findByProjectIdAndUserId(id,user).map(m->m.role).orElseThrow(StorageService::missing);
    }
    void access(String id,String user,boolean write){if(write&&role(id,user).equals("READER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Читатель может только просматривать и скачивать файлы.");else if(!write)role(id,user);}
    void owner(String id,String user){if(!role(id,user).equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Действие доступно владельцу проекта.");}
    int memberLimit(Project p){return "TEAM".equals(p.plan)?25:5;}

    @Transactional public Project create(String user,String name){return create(user,name,"","",null,"blue","orb");}
    @Transactional
    public Project create(String user,String name,String course,String teacher,Instant deadline,String accent,String cover) {
        Project p=new Project();p.ownerId=user;p.name=StorageService.name(name,120);p.course=clean(course,120);p.teacher=clean(teacher,120);p.deadline=deadline;p.accent=validAccent(accent);p.cover=validCover(cover);projects.save(p);
        Membership m=new Membership();m.projectId=p.id;m.userId=user;m.role="OWNER";members.save(m);
        seedTasks(p.id);audit.record(user,p.id,"PROJECT_CREATED","PROJECT",p.id,p.name);return p;
    }

    @Transactional
    public Project update(String id,String actor,String name,String description,String course,String teacher,Instant deadline,String accent,String cover,String status) {
        projects.lockById(id).orElseThrow(StorageService::missing);owner(id,actor);Project p=projects.findById(id).orElseThrow(StorageService::missing);
        if(name!=null)p.name=StorageService.name(name,120);if(description!=null)p.description=clean(description,700);if(course!=null)p.course=clean(course,120);if(teacher!=null)p.teacher=clean(teacher,120);p.deadline=deadline;
        if(accent!=null)p.accent=validAccent(accent);if(cover!=null)p.cover=validCover(cover);if(status!=null)p.status=validStatus(status);projects.save(p);audit.record(actor,p.id,"PROJECT_UPDATED","PROJECT",p.id,p.name);return p;
    }

    @Transactional public void add(String project,String actor,String email,String role) {
        Project p=projects.lockById(project).orElseThrow(StorageService::missing);owner(project,actor);validRole(role);
        if(email==null)throw StorageService.bad("Укажите email участника.");Account a=accounts.findByEmail(AuthController.email(email)).filter(x->!x.blocked).orElseThrow(()->StorageService.bad("Пользователь должен сначала зарегистрироваться в SkyShelf."));
        if(members.findByProjectIdAndUserId(project,a.id).isPresent())throw StorageService.bad("Пользователь уже в проекте.");
        if(members.findByProjectId(project).size()>=memberLimit(p))throw StorageService.bad("Лимит участников для текущего тарифа исчерпан.");
        Membership m=new Membership();m.projectId=project;m.userId=a.id;m.role=role;members.save(m);audit.record(actor,project,"MEMBER_ADDED","MEMBERSHIP",m.id,a.email+" · "+role);
    }
    @Transactional public void change(String project,String actor,String id,String role,boolean remove) {
        projects.lockById(project).orElseThrow(StorageService::missing);owner(project,actor);Membership m=members.findById(id).filter(x->x.projectId.equals(project)).orElseThrow(StorageService::missing);
        if(m.role.equals("OWNER"))throw StorageService.bad("Владельца нельзя удалить или изменить его роль.");
        if(remove){members.delete(m);audit.record(actor,project,"MEMBER_REMOVED","MEMBERSHIP",m.id,m.userId);}else{validRole(role);m.role=role;members.save(m);audit.record(actor,project,"MEMBER_ROLE_CHANGED","MEMBERSHIP",m.id,role);}
    }
    static void validRole(String r){if(!Set.of("MEMBER","READER").contains(r==null?"":r))throw StorageService.bad("Выберите роль участника или читателя.");}

    List<Map<String,Object>> list(String user) {
        return members.findByUserId(user).stream().filter(m->projects.findById(m.projectId).map(p->accounts.findById(p.ownerId).map(a->!a.blocked).orElse(false)).orElse(false)).map(m->{Project p=projects.findById(m.projectId).orElseThrow();return projectView(p,m.role);}).toList();
    }
    Map<String,Object> detail(String id,String user){String role=role(id,user);return projectView(projects.findById(id).orElseThrow(StorageService::missing),role);}
    private Map<String,Object> projectView(Project p,String role) {
        List<ProjectTask> projectTasks=tasks.findByProjectIdOrderByPositionAscCreatedAtAsc(p.id);Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",p.id);out.put("name",p.name);out.put("description",p.description);out.put("course",p.course);out.put("teacher",p.teacher);out.put("deadline",p.deadline);out.put("accent",p.accent);out.put("cover",p.cover);out.put("status",p.status);out.put("finalFileId",p.finalFileId);out.put("role",role);out.put("plan",p.plan);out.put("members",members.findByProjectId(p.id).size());out.put("memberLimit",memberLimit(p));out.put("tasks",projectTasks.size());out.put("completedTasks",projectTasks.stream().filter(t->t.completed).count());out.put("createdAt",p.createdAt);return out;
    }

    List<Map<String,Object>> taskList(String project,String actor){access(project,actor,false);return tasks.findByProjectIdOrderByPositionAscCreatedAtAsc(project).stream().map(this::taskView).toList();}
    @Transactional public ProjectTask createTask(String project,String actor,String title,String assignee,Instant dueAt) {
        access(project,actor,true);ProjectTask t=new ProjectTask();t.projectId=project;t.title=StorageService.name(title,180);t.assigneeId=validAssignee(project,assignee);t.dueAt=dueAt;t.position=tasks.findByProjectIdOrderByPositionAscCreatedAtAsc(project).size();tasks.save(t);audit.record(actor,project,"TASK_CREATED","TASK",t.id,t.title);return t;
    }
    @Transactional public ProjectTask updateTask(String project,String actor,String id,String title,Boolean completed,String assignee,Instant dueAt) {
        access(project,actor,true);ProjectTask t=tasks.findById(id).filter(x->x.projectId.equals(project)).orElseThrow(StorageService::missing);if(title!=null)t.title=StorageService.name(title,180);if(completed!=null)t.completed=completed;if(assignee!=null)t.assigneeId=validAssignee(project,assignee.isBlank()?null:assignee);t.dueAt=dueAt;tasks.save(t);audit.record(actor,project,t.completed?"TASK_COMPLETED":"TASK_UPDATED","TASK",t.id,t.title);return t;
    }
    @Transactional public void deleteTask(String project,String actor,String id){access(project,actor,true);ProjectTask t=tasks.findById(id).filter(x->x.projectId.equals(project)).orElseThrow(StorageService::missing);tasks.delete(t);audit.record(actor,project,"TASK_DELETED","TASK",id,t.title);}
    private String validAssignee(String project,String id){if(id==null||id.isBlank())return null;return members.findByProjectIdAndUserId(project,id).map(m->m.userId).orElseThrow(()->StorageService.bad("Исполнитель должен состоять в проекте."));}
    private Map<String,Object> taskView(ProjectTask t){Map<String,Object> m=new LinkedHashMap<>();m.put("id",t.id);m.put("title",t.title);m.put("completed",t.completed);m.put("assigneeId",t.assigneeId);m.put("assignee",t.assigneeId==null?null:accounts.findById(t.assigneeId).map(a->a.name).orElse("Участник"));m.put("dueAt",t.dueAt);m.put("position",t.position);return m;}

    @Transactional public Project setFinal(String project,String actor,String fileId){projects.lockById(project).orElseThrow(StorageService::missing);owner(project,actor);Project p=projects.findById(project).orElseThrow(StorageService::missing);StoredFile f=files.findById(fileId).filter(x->project.equals(x.projectId)&&!x.trashed).orElseThrow(StorageService::missing);p.finalFileId=f.id;p.status="READY";projects.save(p);audit.record(actor,project,"FINAL_FILE_SELECTED","FILE",f.id,f.name);return p;}
    @Transactional public void clearFinalIf(String project,String fileId){if(project==null)return;projects.findById(project).ifPresent(p->{if(Objects.equals(p.finalFileId,fileId)){p.finalFileId=null;p.status="IN_PROGRESS";projects.save(p);}});}

    record CreatedSubmission(String token,String id,Instant expiresAt){}
    @Transactional public CreatedSubmission createSubmission(String project,String actor,int hours){
        projects.lockById(project).orElseThrow(StorageService::missing);owner(project,actor);Project p=projects.findById(project).orElseThrow(StorageService::missing);if(p.finalFileId==null)throw StorageService.bad("Сначала отметьте итоговый файл проекта.");
        if(hours<1||hours>720)throw StorageService.bad("Срок ссылки — от 1 до 720 часов.");submissions.deleteByProjectId(project);String raw=crypto.newToken();SubmissionLink s=new SubmissionLink();s.token=CryptoService.tokenHash(raw);s.projectId=project;s.expiresAt=Instant.now().plusSeconds(hours*3600L);submissions.save(s);p.status="SUBMITTED";projects.save(p);audit.record(actor,project,"SUBMISSION_LINK_CREATED","PROJECT",project,"Срок "+hours+" ч.");return new CreatedSubmission(raw,s.token,s.expiresAt);
    }
    List<Map<String,Object>> submissionList(String project,String actor){owner(project,actor);return submissions.findByProjectIdOrderByCreatedAtDesc(project).stream().map(s->Map.<String,Object>of("id",s.token,"createdAt",s.createdAt,"expiresAt",s.expiresAt,"views",s.views)).toList();}
    @Transactional public void revokeSubmissions(String project,String actor){owner(project,actor);submissions.deleteByProjectId(project);audit.record(actor,project,"SUBMISSION_LINK_REVOKED","PROJECT",project,"");}
    record PublicSubmission(Project project,StoredFile file,SubmissionLink link){}
    @Transactional public PublicSubmission publicSubmission(String raw,boolean countView){
        String hash=CryptoService.tokenHash(raw==null?"":raw);SubmissionLink link=(countView?submissions.lockByToken(hash):submissions.findById(hash)).filter(s->s.expiresAt.isAfter(Instant.now())).orElseThrow(StorageService::missing);Project p=projects.findById(link.projectId).orElseThrow(StorageService::missing);if(accounts.findById(p.ownerId).map(a->a.blocked).orElse(true)||p.finalFileId==null)throw StorageService.missing();StoredFile f=files.findById(p.finalFileId).filter(x->!x.trashed).orElseThrow(StorageService::missing);if(countView){link.views++;submissions.save(link);}return new PublicSubmission(p,f,link);
    }

    private void seedTasks(String project){for(String title:List.of("Собрать материалы проекта","Проверить роли участников","Отметить итоговый файл","Создать ссылку преподавателю")){ProjectTask t=new ProjectTask();t.projectId=project;t.title=title;t.position=tasks.findByProjectIdOrderByPositionAscCreatedAtAsc(project).size();tasks.save(t);}}
    private static String clean(String value,int max){if(value==null)return "";String v=value.strip();return v.substring(0,Math.min(max,v.length()));}
    private static String validAccent(String value){String v=value==null?"blue":value;if(!Set.of("blue","violet","mint","amber","cyan","rose").contains(v))throw StorageService.bad("Неизвестный акцент проекта.");return v;}
    private static String validCover(String value){String v=value==null?"orb":value;if(!Set.of("orb","grid","aurora","minimal").contains(v))throw StorageService.bad("Неизвестный стиль обложки.");return v;}
    private static String validStatus(String value){if(!Set.of("IN_PROGRESS","READY","SUBMITTED").contains(value))throw StorageService.bad("Неизвестный статус проекта.");return value;}
}
