package ru.skyshelf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/projects")
class ProjectController {
    final ProjectService service;final StorageService storage;final AuthController auth;
    ProjectController(ProjectService p,StorageService s,AuthController a){service=p;storage=s;auth=a;}

    record ProjectInput(String name,String description,String course,String teacher,Instant deadline,String accent,String cover,String status) {}
    record MemberInput(String email,String role) {}
    record TaskInput(String title,Boolean completed,String assigneeId,Instant dueAt) {}
    record SubmissionInput(Integer hours) {}

    @GetMapping List<Map<String,Object>> list(Authentication a){return service.list(auth.current(a).id);}

    @GetMapping("/{id}")
    Map<String,Object> detail(Authentication a,@PathVariable String id){return service.detail(id,auth.current(a).id);}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> create(Authentication a,@RequestBody ProjectInput p){
        Account actor=auth.current(a);Project project=service.create(actor.id,p.name(),p.course(),p.teacher(),p.deadline(),p.accent(),p.cover());
        if(p.description()!=null&&!p.description().isBlank())service.update(project.id,actor.id,null,p.description(),null,null,p.deadline(),null,null,null);
        return service.detail(project.id,actor.id);
    }

    @PatchMapping("/{id}")
    Map<String,Object> update(Authentication a,@PathVariable String id,@RequestBody ProjectInput p){
        String actor=auth.current(a).id;service.update(id,actor,p.name(),p.description(),p.course(),p.teacher(),p.deadline(),p.accent(),p.cover(),p.status());return service.detail(id,actor);
    }

    @GetMapping("/{id}/members")
    List<Map<String,Object>> members(Authentication a,@PathVariable String id){
        service.access(id,auth.current(a).id,false);
        return service.memberRepository().findByProjectId(id).stream().map(m->{Account u=service.accountRepository().findById(m.userId).orElseThrow();Map<String,Object> out=new LinkedHashMap<>();out.put("id",m.id);out.put("userId",u.id);out.put("name",u.name);out.put("email",u.email);out.put("role",m.role);out.put("initials",initials(u.name));return out;}).toList();
    }

    @PostMapping("/{id}/members")
    Map<String,Boolean> add(Authentication a,@PathVariable String id,@RequestBody MemberInput p){service.add(id,auth.current(a).id,p.email(),p.role());return Map.of("ok",true);}
    @PatchMapping("/{id}/members/{member}")
    Map<String,Boolean> change(Authentication a,@PathVariable String id,@PathVariable String member,@RequestBody MemberInput p){service.change(id,auth.current(a).id,member,p.role(),false);return Map.of("ok",true);}
    @DeleteMapping("/{id}/members/{member}")
    Map<String,Boolean> remove(Authentication a,@PathVariable String id,@PathVariable String member){service.change(id,auth.current(a).id,member,null,true);return Map.of("ok",true);}

    @GetMapping("/{id}/tasks")
    List<Map<String,Object>> tasks(Authentication a,@PathVariable String id){return service.taskList(id,auth.current(a).id);}
    @PostMapping("/{id}/tasks") @ResponseStatus(HttpStatus.CREATED)
    List<Map<String,Object>> createTask(Authentication a,@PathVariable String id,@RequestBody TaskInput p){String actor=auth.current(a).id;service.createTask(id,actor,p.title(),p.assigneeId(),p.dueAt());return service.taskList(id,actor);}
    @PatchMapping("/{id}/tasks/{task}")
    List<Map<String,Object>> updateTask(Authentication a,@PathVariable String id,@PathVariable String task,@RequestBody TaskInput p){String actor=auth.current(a).id;service.updateTask(id,actor,task,p.title(),p.completed(),p.assigneeId(),p.dueAt());return service.taskList(id,actor);}
    @DeleteMapping("/{id}/tasks/{task}")
    List<Map<String,Object>> deleteTask(Authentication a,@PathVariable String id,@PathVariable String task){String actor=auth.current(a).id;service.deleteTask(id,actor,task);return service.taskList(id,actor);}

    @PostMapping("/{id}/final/{fileId}")
    Map<String,Object> setFinal(Authentication a,@PathVariable String id,@PathVariable String fileId){String actor=auth.current(a).id;service.setFinal(id,actor,fileId);return service.detail(id,actor);}

    @GetMapping("/{id}/submissions")
    List<Map<String,Object>> submissions(Authentication a,@PathVariable String id){return service.submissionList(id,auth.current(a).id);}
    @PostMapping("/{id}/submissions") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> createSubmission(Authentication a,@PathVariable String id,@RequestBody SubmissionInput p){
        ProjectService.CreatedSubmission created=service.createSubmission(id,auth.current(a).id,p.hours()==null?168:p.hours());return Map.of("token",created.token(),"id",created.id(),"expiresAt",created.expiresAt());
    }
    @DeleteMapping("/{id}/submissions")
    Map<String,Boolean> revokeSubmissions(Authentication a,@PathVariable String id){service.revokeSubmissions(id,auth.current(a).id);return Map.of("ok",true);}

    @GetMapping("/{id}/export")
    ResponseEntity<ByteArrayResource> export(Authentication a,@PathVariable String id) throws Exception {
        String actor=auth.current(a).id;service.access(id,actor,false);Project p=service.projectRepository().findById(id).orElseThrow(StorageService::missing);byte[] bytes=archive(p,storage.workspaceFiles(actor,id));
        return FileController.attachment(fileSafe(p.name)+"-SkyShelf.zip","application/zip",bytes);
    }

    private byte[] archive(Project project,List<StoredFile> projectFiles) throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();Set<String> names=new HashSet<>();
        try(ZipOutputStream zip=new ZipOutputStream(buffer,StandardCharsets.UTF_8)){
            String manifest="SkyShelf · пакет учебного проекта\n\nПроект: "+project.name+"\nКурс: "+project.course+"\nПреподаватель: "+project.teacher+"\nСтатус: "+project.status+"\nЭкспортирован: "+Instant.now()+"\n\nФайлы: "+projectFiles.stream().filter(f->!f.trashed).count()+"\nШифрование в хранилище: AES-256-GCM\nКонтроль целостности: SHA-256\n";
            write(zip,"SkyShelf-manifest.txt",manifest.getBytes(StandardCharsets.UTF_8));
            for(StoredFile file:projectFiles.stream().filter(f->!f.trashed).toList()){
                String candidate=(Objects.equals(project.finalFileId,file.id)?"FINAL_":"")+fileSafe(file.name);String unique=candidate;int n=2;while(!names.add(unique)){unique=n+"_"+candidate;n++;}write(zip,"files/"+unique,storage.content(file));
            }
        }
        return buffer.toByteArray();
    }

    private static void write(ZipOutputStream zip,String name,byte[] bytes) throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private static String fileSafe(String name){return name.replaceAll("[^\\p{L}\\p{N}._ -]","_").strip();}
    private static String initials(String name){return Arrays.stream(name.trim().split("\\s+")).limit(2).map(x->x.substring(0,1).toUpperCase(Locale.ROOT)).reduce("",String::concat);}
}

@RestController @RequestMapping("/api/public/projects")
class PublicProjectController {
    private final ProjectService projects;private final StorageService storage;
    PublicProjectController(ProjectService p,StorageService s){projects=p;storage=s;}

    @GetMapping("/{token}")
    Map<String,Object> info(@PathVariable String token){
        ProjectService.PublicSubmission data=projects.publicSubmission(token,true);Project p=data.project();StoredFile f=data.file();Map<String,Object> out=new LinkedHashMap<>();
        out.put("name",p.name);out.put("description",p.description);out.put("course",p.course);out.put("teacher",p.teacher);out.put("deadline",p.deadline);out.put("status",p.status);out.put("accent",p.accent);out.put("cover",p.cover);out.put("expiresAt",data.link().expiresAt);out.put("views",data.link().views);out.put("members",projects.memberRepository().findByProjectId(p.id).stream().map(m->projects.accountRepository().findById(m.userId).map(a->Map.of("name",a.name,"role",m.role)).orElse(Map.of())).toList());
        out.put("finalFile",Map.of("name",f.name,"size",f.size,"versionCount",f.versionCount,"checksum",f.checksum,"updatedAt",f.updatedAt));return out;
    }

    @GetMapping("/{token}/download")
    ResponseEntity<ByteArrayResource> download(@PathVariable String token) throws Exception {ProjectService.PublicSubmission data=projects.publicSubmission(token,false);StoredFile f=data.file();return FileController.attachment(f.name,f.mimeType,storage.content(f));}
}
